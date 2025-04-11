package ca.lwi.trqcbot.donations;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DonationsHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DonationsHandler.class);
    private static final MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection("data_history");

    // Couleurs pour les trois premiers donateurs
    private static final Color FIRST_PLACE_COLOR = new Color(255, 215, 0); // Or
    private static final Color SECOND_PLACE_COLOR = new Color(192, 192, 192); // Argent
    private static final Color THIRD_PLACE_COLOR = new Color(205, 127, 50); // Bronze
    private static final Color DEFAULT_COLOR = new Color(47, 49, 54); // Couleur Discord par défaut
    private static final Color TOP_TEN_COLOR = new Color(0, 120, 215); // Bleu pour le top 10

    // Stockage des pages de pagination par utilisateur
    private static final Map<String, Integer> userPagination = new HashMap<>();
    private static final Map<String, Long> paginationTimestamps = new HashMap<>();
    private static final int PAGINATION_TIMEOUT_MINUTES = 5;

    /**
     * Récupère la liste de tous les donateurs triés par montant total
     * @return Liste des donateurs
     */
    public static List<Document> getAllDonors() {
        try {
            Document hallOfFame = collection.find(Filters.eq("type", "hall_of_fame")).first();
            if (hallOfFame == null) {
                LOGGER.error("Hall of Fame document not found in database");
                return new ArrayList<>();
            }

            List<Document> donations = hallOfFame.getList("donations", Document.class);
            donations.sort(Comparator.comparingInt(doc -> -doc.getInteger("total"))); // Tri décroissant

            return donations;
        } catch (Exception e) {
            LOGGER.error("Error retrieving donors: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Ajoute un don à un donateur existant
     * @param donorName Nom du donateur
     * @param amount Montant du don
     * @return true si l'ajout a réussi, false sinon
     */
    public static boolean addDonationToExistingDonor(String donorName, int amount) {
        try {
            UpdateResult result = collection.updateOne(
                    Filters.and(
                            Filters.eq("type", "hall_of_fame"),
                            Filters.elemMatch("donations", Filters.eq("name", donorName))
                    ),
                    Updates.inc("donations.$.total", amount)
            );

            if (result.getModifiedCount() > 0) {
                // Mettre à jour le total global
                collection.updateOne(
                        Filters.eq("type", "hall_of_fame"),
                        Updates.inc("total", amount)
                );
                LOGGER.info("Added {} to donor {}", amount, donorName);
                return true;
            } else {
                LOGGER.warn("Donor {} not found", donorName);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error adding donation to existing donor: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Ajoute un nouveau donateur
     * @param donorName Nom du donateur
     * @param amount Montant du don initial
     * @return true si l'ajout a réussi, false sinon
     */
    public static boolean addNewDonor(String donorName, int amount) {
        try {
            Document newDonor = new Document()
                    .append("name", donorName)
                    .append("total", amount);

            UpdateResult result = collection.updateOne(
                    Filters.eq("type", "hall_of_fame"),
                    Updates.combine(
                            Updates.push("donations", newDonor),
                            Updates.inc("total", amount)
                    )
            );

            if (result.getModifiedCount() > 0) {
                LOGGER.info("Added new donor {} with initial donation of {}", donorName, amount);
                return true;
            } else {
                LOGGER.warn("Failed to add new donor {}", donorName);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error adding new donor: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Génère un embed pour une page spécifique
     * @param donorsList Liste des donateurs
     * @param page Numéro de page (0 = podium + top 10, 1+ = autres pages)
     * @return MessageEmbed
     */
    public static MessageEmbed generateDonorsEmbed(List<Document> donorsList, int page, int totalPages) {
        EmbedBuilder embed = new EmbedBuilder();
        int totalDonations = getTotalDonations();

        if (donorsList.isEmpty()) {
            embed.setTitle("⭐ Hall of Fame").setDescription("Aucun donateur trouvé.").setColor(DEFAULT_COLOR);
            return embed.build();
        }

        if (page == 0) {
            embed.setTitle("🏆 Hall of Fame des Donateurs").setColor(FIRST_PLACE_COLOR).setFooter("Total des dons: " + totalDonations + "$ | Page " + (page+1) + "/" + totalPages);
            StringBuilder description = generatePodiumAndTop10Description(donorsList, false);
            embed.setDescription(description.toString());
        } else {
            int startIndex = 10 + ((page - 1) * 25);
            embed.setTitle("📜 Top " + (startIndex+1) + " — " + (startIndex + 25)).setColor(DEFAULT_COLOR).setFooter("Total des dons: " + totalDonations + " $ | Page " + (page+1) + "/" + totalPages);
            StringBuilder description = new StringBuilder();
            int endIndex = Math.min(startIndex + 25, donorsList.size());

            if (startIndex >= donorsList.size()) {
                description.append("Aucun donateur supplémentaire.");
            } else {
                for (int i = startIndex; i < endIndex; i++) {
                    Document donor = donorsList.get(i);
                    String name = donor.getString("name");
                    int total = donor.getInteger("total");
                    description.append("**#").append(i + 1).append("** ").append(name).append(" — ").append(total).append(" $\n");
                }
            }

            embed.setDescription(description.toString());
        }

        return embed.build();
    }

    /**
     * Calcule le nombre total de pages nécessaires
     * @param donorsCount Nombre total de donateurs
     * @return Nombre de pages
     */
    public static int calculateTotalPages(int donorsCount) {
        // Page 0: Podium + Top 10
        // Pages 1+: Autres donateurs (25 par page)
        if (donorsCount <= 10) {
            return 1;
        } else {
            return 1 + (int) Math.ceil((donorsCount - 10) / 25.0);
        }
    }

    /**
     * Met à jour l'embed et les boutons pour une page spécifique
     */
    public static MessageEmbed getPageEmbed(String userId, int page) {
        List<Document> donors = getAllDonors();
        int totalPages = calculateTotalPages(donors.size());

        // Assurer que la page est dans les limites valides
        page = Math.max(0, Math.min(totalPages - 1, page));

        // Stocker la page actuelle pour cet utilisateur
        userPagination.put(userId, page);
        paginationTimestamps.put(userId, System.currentTimeMillis());

        // Nettoyer les entrées expirées (toutes les 100 accès pour éviter de surcharger)
        if (Math.random() < 0.01) { // ~1% de chance
            cleanupExpiredPagination();
        }

        return generateDonorsEmbed(donors, page, totalPages);
    }

    /**
     * Nettoie les entrées de pagination expirées
     */
    private static void cleanupExpiredPagination() {
        long cutoffTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(PAGINATION_TIMEOUT_MINUTES);

        paginationTimestamps.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);
        userPagination.keySet().removeIf(key -> !paginationTimestamps.containsKey(key));

        LOGGER.debug("Cleaned up pagination cache. Remaining entries: {}", userPagination.size());
    }

    /**
     * Crée les boutons de pagination
     * @param userId ID de l'utilisateur
     * @return Liste de boutons
     */
    public static List<Button> createPaginationButtons(String userId) {
        List<Button> buttons = new ArrayList<>();
        List<Document> donors = getAllDonors();
        int totalPages = calculateTotalPages(donors.size());
        int currentPage = userPagination.getOrDefault(userId, 0);
        buttons.add(Button.primary("donors_first_" + userId, "⏮️ Première").withDisabled(currentPage == 0));
        buttons.add(Button.primary("donors_prev_" + userId, "◀️ Précédent").withDisabled(currentPage == 0));
        buttons.add(Button.primary("donors_next_" + userId, "Suivant ▶️").withDisabled(currentPage >= totalPages - 1));
        buttons.add(Button.primary("donors_last_" + userId, "Dernière ⏭️").withDisabled(currentPage >= totalPages - 1));
        return buttons;
    }

    /**
     * Envoie un message permanent avec le classement des donateurs dans un canal
     * @param channelId ID du canal
     * @param forceNewMessage Créer un nouveau message même si un existe déjà
     * @return CompletableFuture<Void>
     */
    public static CompletableFuture<Void> sendPermanentDonorsMessage(String channelId, boolean forceNewMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            TextChannel channel = Main.getJda().getTextChannelById(channelId);
            if (channel == null) {
                LOGGER.error("Channel with ID {} not found", channelId);
                future.completeExceptionally(new IllegalArgumentException("Channel not found"));
                return future;
            }

            // Création d'un message sans boutons de pagination pour le message permanent
            List<Document> donors = getAllDonors();
            EmbedBuilder embed = new EmbedBuilder();
            int totalDonations = getTotalDonations();
            embed.setTitle("🏆 Hall of Fame").setFooter("Total des dons: " + totalDonations + " $");

            // Combiner le podium et le top 10 comme dans la page 0 de la pagination
            StringBuilder description = generatePodiumAndTop10Description(donors, true);
            embed.setDescription(description.toString());
            embed.setColor(FIRST_PLACE_COLOR);

            // Ajout d'un bouton pour voir le classement complet
            Button viewButton = Button.primary("donors_view", "Voir le classement complet");

            // Recherche d'un message existant
            Document storedMessage = Main.getMongoConnection().getDatabase().getCollection("messages")
                    .find(Filters.and(
                            Filters.eq("channelId", channelId),
                            Filters.eq("type", "donors_message")
                    )).first();

            if (storedMessage != null && !forceNewMessage) {
                String messageId = storedMessage.getString("messageId");
                channel.retrieveMessageById(messageId).queue(
                        message -> {
                            message.editMessageEmbeds(embed.build()).setActionRow(viewButton).queue(
                                    updated -> {
                                        LOGGER.info("Updated permanent donors message in channel {}", channelId);
                                        future.complete(null);
                                    },
                                    error -> {
                                        LOGGER.error("Failed to update permanent donors message: {}", error.getMessage());
                                        createNewPermanentDonorsMessage(channel, embed.build(), viewButton, future);
                                    }
                            );
                        },
                        error -> {
                            LOGGER.error("Failed to retrieve permanent donors message: {}", error.getMessage());
                            createNewPermanentDonorsMessage(channel, embed.build(), viewButton, future);
                        }
                );
            } else {
                createNewPermanentDonorsMessage(channel, embed.build(), viewButton, future);
            }
        } catch (Exception e) {
            LOGGER.error("Error in sendPermanentDonorsMessage: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Génère la description combinée du podium et du top 10
     * @param donorsList Liste des donateurs
     * @param includeViewMoreMessage Indique s'il faut inclure le message pour voir plus de donateurs
     * @return StringBuilder contenant la description formatée
     */
    private static StringBuilder generatePodiumAndTop10Description(List<Document> donorsList, boolean includeViewMoreMessage) {
        StringBuilder description = new StringBuilder();

        // Section podium
        description.append("### 🌟 Top 10\n\n");
        for (int i = 0; i < Math.min(10, donorsList.size()); i++) {
            Document donor = donorsList.get(i);
            String name = donor.getString("name");
            int total = donor.getInteger("total");
            String prefix = switch (i) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> "**#" + (i + 1) + "**";
            };
            description.append(prefix).append(" ").append(name).append(" — ").append(total).append(" $\n");
        }
        if (includeViewMoreMessage && donorsList.size() > 10) description.append("\n*Cliquez sur le bouton ci-dessous pour voir la liste complète des donateurs.*");
        return description;
    }

    private static void createNewPermanentDonorsMessage(TextChannel channel, MessageEmbed embed, Button viewButton, CompletableFuture<Void> future) {
        channel.sendMessageEmbeds(embed).addActionRow(viewButton).queue(
                message -> {
                    Document doc = new Document()
                            .append("channelId", channel.getId())
                            .append("messageId", message.getId())
                            .append("type", "donors_message")
                            .append("lastUpdated", System.currentTimeMillis());

                    Main.getMongoConnection().getDatabase().getCollection("messages").updateOne(
                            Filters.and(
                                    Filters.eq("channelId", channel.getId()),
                                    Filters.eq("type", "donors_message")
                            ),
                            new Document("$set", doc),
                            new com.mongodb.client.model.UpdateOptions().upsert(true)
                    );

                    LOGGER.info("New permanent donors message created in channel {}", channel.getId());
                    future.complete(null);
                },
                error -> {
                    LOGGER.error("Failed to send permanent donors message: {}", error.getMessage());
                    future.completeExceptionally(error);
                }
        );
    }

    /**
     * Gère le bouton pour voir le classement complet
     * @param event L'événement du bouton
     */
    public static void handleViewButton(ButtonInteractionEvent event) {
        String userId = event.getUser().getId();
        MessageEmbed embed = getPageEmbed(userId, 0);
        List<Button> buttons = createPaginationButtons(userId);

        // Envoyer un message éphémère avec l'embed et les boutons de pagination
        event.replyEmbeds(embed)
                .addActionRow(buttons)
                .setEphemeral(true)  // Important: rend le message visible uniquement pour l'utilisateur qui a cliqué
                .queue();
    }

    /**
     * Récupère le montant total des dons
     * @return Montant total des dons
     */
    public static int getTotalDonations() {
        try {
            Document hallOfFame = collection.find(Filters.eq("type", "hall_of_fame")).first();
            if (hallOfFame == null) {
                return 0;
            }
            return hallOfFame.getInteger("total", 0);
        } catch (Exception e) {
            LOGGER.error("Error getting total donations: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Liste tous les noms des donateurs pour utilisation dans un sélecteur
     * @return Liste des noms des donateurs
     */
    public static List<String> getDonorNamesList() {
        List<String> donorNames = new ArrayList<>();
        List<Document> donors = getAllDonors();

        for (Document donor : donors) {
            donorNames.add(donor.getString("name"));
        }

        return donorNames;
    }

    /**
     * Gestion de la pagination
     * @param event L'événement du bouton
     * @param action Action de pagination (first, prev, next, last)
     */
    public static void handlePaginationButton(ButtonInteractionEvent event, String action) {
        String userId = event.getUser().getId();
        int currentPage = userPagination.getOrDefault(userId, 0);
        List<Document> donors = getAllDonors();
        int totalPages = calculateTotalPages(donors.size());

        int newPage = switch (action) {
            case "first" -> 0;
            case "prev" -> Math.max(0, currentPage - 1);
            case "next" -> Math.min(totalPages - 1, currentPage + 1);
            case "last" -> totalPages - 1;
            default -> currentPage;
        };

        // Mettre à jour la page pour cet utilisateur
        userPagination.put(userId, newPage);
        paginationTimestamps.put(userId, System.currentTimeMillis());

        // Générer le nouvel embed et les boutons
        MessageEmbed embed = getPageEmbed(userId, newPage);
        List<Button> buttons = createPaginationButtons(userId);

        // Mettre à jour le message éphémère
        event.editMessageEmbeds(embed)
                .setActionRow(buttons)
                .queue();
    }
}