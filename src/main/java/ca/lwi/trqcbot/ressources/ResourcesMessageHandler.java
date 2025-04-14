package ca.lwi.trqcbot.ressources;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ResourcesMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourcesMessageHandler.class);
    private static final String COLLECTION_NAME = "resources";
    private static final MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);

    // Store resources message content
    private static final String TARGET_CHANNEL_ID = "1357187116731469874";

    // MongoDB keys for different sections
    private static final String KEY_ROLES = "roles";
    private static final String KEY_CATEGORIES = "categories";
    private static final String KEY_IMPORTANT_CHANNELS = "importantChannels";
    private static final String KEY_USEFUL_LINKS = "usefulLinks";
    private static final String KEY_SERVER_NAME = "serverName";

    /**
     * Updates the resources message in the specified channel
     * @param forceNewMessage If true, creates a new message even if one exists
     * @return CompletableFuture that completes when operation is done
     */
    public static CompletableFuture<Void> updateResourcesMessages(boolean forceNewMessages) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            TextChannel channel = Main.getJda().getTextChannelById(TARGET_CHANNEL_ID);
            if (channel == null) {
                LOGGER.error("Resources channel with ID {} not found", TARGET_CHANNEL_ID);
                future.completeExceptionally(new IllegalArgumentException("Channel not found"));
                return future;
            }

            // Générer le contenu des différentes sections
            List<String> messageParts = generateResourceMessageParts(channel.getGuild());

            Document storedData = collection.find(Filters.eq("channelId", TARGET_CHANNEL_ID)).first();

            if (storedData != null && !forceNewMessages && storedData.containsKey("messageIds")) {
                List<String> messageIds = storedData.getList("messageIds", String.class, new ArrayList<>());

                // Si le nombre de messages stockés correspond au nombre de parties que nous avons
                if (messageIds.size() == messageParts.size()) {
                    updateExistingMessages(channel, messageIds, messageParts, future);
                } else {
                    // Le nombre de messages ne correspond pas, créer de nouveaux messages
                    deleteExistingMessages(channel, messageIds);
                    createNewResourcesMessages(channel, messageParts, future);
                }
            } else {
                // Aucun message stocké ou force new messages, créer de nouveaux messages
                if (storedData != null && storedData.containsKey("messageId")) {
                    // Supprimer l'ancien message unique s'il existe
                    String oldMessageId = storedData.getString("messageId");
                    channel.retrieveMessageById(oldMessageId)
                            .queue(message -> message.delete().queue(), error -> {/* Ignorer l'erreur si le message n'existe plus */});
                }
                createNewResourcesMessages(channel, messageParts, future);
            }
        } catch (Exception e) {
            LOGGER.error("Error in updateResourcesMessages: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Creates a new resources message in the channel
     */
    private static List<String> generateResourceMessageParts(Guild guild) {
        Document configDoc = getOrCreateConfigDocument();
        String serverName = configDoc.getString(KEY_SERVER_NAME);

        List<String> messageParts = new ArrayList<>();

        // Partie 1: Introduction
        String intro = "# 📚 Bienvenue sur le serveur de " + serverName + " !\n\n" +
                "Ici, tu peux discuter de NHL 25, suivre les vidéos du créateur et interagir avec la " +
                "communauté. Avant de commencer, voici quelques infos essentielles !";
        messageParts.add(intro);

        // Partie 2: Catégories
        List<Document> categories = configDoc.getList(KEY_CATEGORIES, Document.class, new ArrayList<>());
        if (!categories.isEmpty()) {
            StringBuilder catSection = new StringBuilder();
            catSection.append("## 📂 Les catégories\n");
            for (Document category : categories) {
                String emoji = category.getString("emoji");
                String name = category.getString("name");
                String description = category.getString("description");
                catSection.append("• ").append(emoji).append(" **").append(name).append("** → ").append(description).append("\n");
            }
            messageParts.add(catSection.toString());
        }

        // Partie 3: Rôles
        List<Document> roles = configDoc.getList(KEY_ROLES, Document.class, new ArrayList<>());
        if (!roles.isEmpty()) {
            StringBuilder roleSection = new StringBuilder();
            roleSection.append("⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯\n");
            roleSection.append("## 👥 Les rôles\n");
            for (Document roleDoc : roles) {
                String id = roleDoc.getString("id");
                String description = roleDoc.getString("description");
                roleSection.append("• <@&").append(id).append("> → ").append(description).append("\n");
            }
            messageParts.add(roleSection.toString());
        }

        // Partie 4: Canaux importants
        List<Document> importantChannels = configDoc.getList(KEY_IMPORTANT_CHANNELS, Document.class, new ArrayList<>());
        if (!importantChannels.isEmpty()) {
            StringBuilder channelSection = new StringBuilder();
            channelSection.append("⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯\n");
            channelSection.append("## 📌 Salons importants\n");
            for (Document channelDoc : importantChannels) {
                String id = channelDoc.getString("id");
                String description = channelDoc.getString("description");
                channelSection.append("• <#").append(id).append("> → ").append(description).append("\n");
            }
            messageParts.add(channelSection.toString());
        }

        // Partie 5: Liens utiles
        List<Document> usefulLinks = configDoc.getList(KEY_USEFUL_LINKS, Document.class, new ArrayList<>());
        if (!usefulLinks.isEmpty()) {
            StringBuilder linkSection = new StringBuilder();
            linkSection.append("⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯\n");
            String linkTitle = usefulLinks.size() > 1 ? "## 🔗 Liens utiles" : "## 🔗 Lien utile";
            linkSection.append(linkTitle).append("\n");
            for (Document link : usefulLinks) {
                String emoji = link.getString("emoji");
                String name = link.getString("name");
                String url = link.getString("url");
                linkSection.append("• ").append(emoji).append(" [").append(name).append("](").append(url).append(")\n");
            }
            messageParts.add(linkSection.toString());
        }

        return messageParts;
    }

    private static void updateExistingMessages(TextChannel channel, List<String> messageIds,
                                               List<String> messageParts, CompletableFuture<Void> future) {
        AtomicInteger completedUpdates = new AtomicInteger(0);
        AtomicBoolean failedUpdate = new AtomicBoolean(false);

        for (int i = 0; i < messageIds.size(); i++) {
            final int index = i;
            channel.retrieveMessageById(messageIds.get(i)).queue(
                    message -> {
                        message.editMessage(messageParts.get(index)).queue(
                                updated -> {
                                    if (completedUpdates.incrementAndGet() == messageIds.size() && !failedUpdate.get()) {
                                        future.complete(null);
                                    }
                                },
                                error -> {
                                    if (!failedUpdate.get()) {
                                        failedUpdate.set(true);
                                        LOGGER.error("Failed to update message part {}: {}", index, error.getMessage());
                                        // Si la mise à jour échoue, supprimer tous les messages et en créer de nouveaux
                                        deleteExistingMessages(channel, messageIds);
                                        createNewResourcesMessages(channel, messageParts, future);
                                    }
                                }
                        );
                    },
                    error -> {
                        if (!failedUpdate.get()) {
                            failedUpdate.set(true);
                            LOGGER.error("Failed to retrieve message part {}: {}", index, error.getMessage());
                            // Si la récupération échoue, supprimer tous les messages et en créer de nouveaux
                            deleteExistingMessages(channel, messageIds);
                            createNewResourcesMessages(channel, messageParts, future);
                        }
                    }
            );
        }
    }

    private static void deleteExistingMessages(TextChannel channel, List<String> messageIds) {
        for (String messageId : messageIds) {
            channel.retrieveMessageById(messageId).queue(
                    message -> message.delete().queue(
                            success -> LOGGER.info("Deleted old message: {}", messageId),
                            error -> LOGGER.warn("Failed to delete message {}: {}", messageId, error.getMessage())
                    ),
                    error -> LOGGER.warn("Failed to retrieve message for deletion {}: {}", messageId, error.getMessage())
            );
        }
    }

    private static void createNewResourcesMessages(TextChannel channel, List<String> messageParts,
                                                   CompletableFuture<Void> future) {
        List<String> newMessageIds = new ArrayList<>();
        sendMessagesSequentially(channel, messageParts, 0, newMessageIds, future);
    }

    private static void sendMessagesSequentially(TextChannel channel, List<String> messageParts, int index,
                                                 List<String> messageIds, CompletableFuture<Void> future) {
        if (index >= messageParts.size()) {
            // Tous les messages ont été envoyés avec succès
            Document doc = new Document()
                    .append("channelId", channel.getId())
                    .append("messageIds", messageIds)
                    .append("lastUpdated", System.currentTimeMillis());
            collection.updateOne(
                    Filters.eq("channelId", channel.getId()),
                    new Document("$set", doc),
                    new UpdateOptions().upsert(true)
            );
            future.complete(null);
            LOGGER.info("All resource messages created in channel {}", channel.getId());
            return;
        }

        channel.sendMessage(messageParts.get(index)).queue(
                message -> {
                    messageIds.add(message.getId());
                    // Passer au message suivant
                    sendMessagesSequentially(channel, messageParts, index + 1, messageIds, future);
                },
                error -> {
                    LOGGER.error("Failed to send message part {}: {}", index, error.getMessage());
                    future.completeExceptionally(error);
                }
        );
    }

    /**
     * Gets or creates the configuration document in MongoDB
     */
    private static Document getOrCreateConfigDocument() {
        Document configDoc = collection.find(Filters.eq("type", "config")).first();
        if (configDoc == null) {
            configDoc = new Document("type", "config")
                    .append(KEY_SERVER_NAME, "TheRockQC")
                    .append(KEY_ROLES, createDefaultRoles())
                    .append(KEY_CATEGORIES, createDefaultCategories())
                    .append(KEY_IMPORTANT_CHANNELS, createDefaultImportantChannels())
                    .append(KEY_USEFUL_LINKS, createDefaultUsefulLinks());

            collection.insertOne(configDoc);
        }
        return configDoc;
    }

    /**
     * Updates a specific section of the resources message
     * @param sectionKey Section key to update
     * @param newData New data for the section
     * @return CompletableFuture that completes when the operation is done
     */
    public static CompletableFuture<Void> updateSection(String sectionKey, List<Document> newData) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            collection.updateOne(
                    Filters.eq("type", "config"),
                    new Document("$set", new Document(sectionKey, newData)),
                    new UpdateOptions().upsert(true)
            );

            // Update the message after section change
            return updateResourcesMessages(false);
        } catch (Exception e) {
            LOGGER.error("Error updating section {}: {}", sectionKey, e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Updates the server name
     * @param serverName New server name
     * @return CompletableFuture that completes when the operation is done
     */
    public static CompletableFuture<Void> updateServerName(String serverName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            collection.updateOne(
                    Filters.eq("type", "config"),
                    new Document("$set", new Document(KEY_SERVER_NAME, serverName)),
                    new UpdateOptions().upsert(true)
            );

            // Update the message after section change
            return updateResourcesMessages(false);
        } catch (Exception e) {
            LOGGER.error("Error updating server name: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    // Default data creators for initial setup - unchanged from original code

    private static List<Document> createDefaultRoles() {
        List<Document> roles = new ArrayList<>();
        roles.add(new Document()
                .append("name", "Recrue")
                .append("description", "Nouveau membre"));
        roles.add(new Document()
                .append("name", "Joueur")
                .append("description", "Membre régulier"));
        roles.add(new Document()
                .append("name", "Vétéran")
                .append("description", "Abonné YouTube (payant)"));
        roles.add(new Document()
                .append("name", "Entraîneur")
                .append("description", "Modérateur du serveur"));
        return roles;
    }

    private static List<Document> createDefaultCategories() {
        List<Document> categories = new ArrayList<>();
        categories.add(new Document()
                .append("emoji", "📢")
                .append("name", "Informations")
                .append("description", "Toutes les annonces et informations importantes."));
        categories.add(new Document()
                .append("emoji", "👕")
                .append("name", "La Chambre")
                .append("description", "Discussions générales, échange entre membres."));
        categories.add(new Document()
                .append("emoji", "🧊")
                .append("name", "Sur la glace")
                .append("description", "Discussions sur la NHL, les Canadiens de Montréal et sur le jeu."));
        categories.add(new Document()
                .append("emoji", "❓")
                .append("name", "Support")
                .append("description", "Pour demander de l'aide, signaler des problèmes ou faire des suggestions."));
        return categories;
    }

    private static List<Document> createDefaultImportantChannels() {
        List<Document> channels = new ArrayList<>();
        channels.add(new Document()
                .append("name", "#annonces")
                .append("description", "Infos importantes sur le serveur."));
        channels.add(new Document()
                .append("name", "#vidéos")
                .append("description", "Nouvelles vidéos YouTube et lives."));
        channels.add(new Document()
                .append("name", "#ressources")
                .append("description", "Ce salon, pour toutes les infos utiles !"));
        channels.add(new Document()
                .append("name", "#hall-of-fame")
                .append("description", "Met en avant les plus grands exploits des membres."));
        channels.add(new Document()
                .append("name", "#moments-forts")
                .append("description", "Partage des clips ou des actions mémorables du jeu, de la NHL ou de TheRock."));
        channels.add(new Document()
                .append("name", "#suggestions")
                .append("description", "Propose tes idées pour le serveur, les vidéos, la chaîne YouTube, les avantages ou même l'équipe El Paso Jacks de TheRock."));
        return channels;
    }

    private static List<Document> createDefaultUsefulLinks() {
        List<Document> links = new ArrayList<>();
        links.add(new Document()
                .append("emoji", "📺")
                .append("name", "Accéder à la Chaîne YouTube")
                .append("url", "https://www.youtube.com/channel/TheRockQC"));
        return links;
    }
}