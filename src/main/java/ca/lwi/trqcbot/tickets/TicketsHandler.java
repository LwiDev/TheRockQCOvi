package ca.lwi.trqcbot.tickets;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TicketsHandler extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketsHandler.class);

    // ID du channel de création de tickets
    private final String ticketChannelId = "1357158503541641266";

    // Constantes pour les IDs des boutons
    private static final String CREATE_TICKET_BUTTON = "create_ticket";
    private static final String ARCHIVE_TICKET_BUTTON = "archive_ticket";
    private static final String UNARCHIVE_SELECT_MENU = "unarchive_ticket_select";

    // Collection MongoDB pour stocker les tickets
    private MongoCollection<Document> ticketsCollection;

    public TicketsHandler() {
        this.ticketsCollection = Main.getMongoConnection().getDatabase().getCollection("tickets");
    }

    /**
     * Initialise le système de tickets en envoyant le message de création de ticket
     * dans le channel dédié.
     *
     * @param guild La guilde (serveur Discord)
     */
    public void initialize(Guild guild) {
        TextChannel ticketChannel = guild.getTextChannelById(ticketChannelId);
        if (ticketChannel == null) {
            LOGGER.error("Channel de tickets non trouvé: {}", ticketChannelId);
            return;
        }

        // Supprime les anciens messages pour éviter les doublons
        ticketChannel.getIterableHistory().queue(history -> {
            if (!history.isEmpty()) {
                ticketChannel.purgeMessages(history);
            }

            // Crée et envoie le nouveau message de création de ticket
            sendTicketCreationMessage(ticketChannel);
        });
    }

    /**
     * Envoie le message de création de ticket dans le channel spécifié
     *
     * @param channel Le channel où envoyer le message
     */
    private void sendTicketCreationMessage(TextChannel channel) {
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Système de tickets")
                .setDescription("Besoin d'aide ? Une question ? Un problème ? Créez un ticket en cliquant sur le bouton ci-dessous et un membre de notre équipe vous assistera dès que possible.")
                .setColor(Color.BLUE)
                .addField("Comment ça fonctionne ?", "En cliquant sur le bouton, un nouveau thread sera créé spécifiquement pour votre demande. Veuillez fournir le plus de détails possible concernant votre requête.", false)
                .addField("Délai d'inactivité", "Les tickets inactifs pendant plus d'une semaine seront automatiquement archivés.", false)
                .build();

        Button createButton = Button.primary(CREATE_TICKET_BUTTON, "Créer un ticket")
                .withEmoji(Emoji.fromUnicode("🎫"));

        channel.sendMessageEmbeds(embed)
                .addActionRow(createButton)
                .queue();

        LOGGER.info("Message de création de ticket envoyé dans le channel {}", channel.getName());
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();

        switch (buttonId) {
            case CREATE_TICKET_BUTTON:
                handleCreateTicket(event);
                break;
            case ARCHIVE_TICKET_BUTTON:
                handleArchiveButton(event);
                break;
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (event.getComponentId().equals(UNARCHIVE_SELECT_MENU)) {
            String threadId = event.getValues().get(0);

            Guild guild = event.getGuild();
            if (guild == null) {
                event.reply("Impossible de trouver le serveur.").setEphemeral(true).queue();
                return;
            }

            ThreadChannel thread = guild.getThreadChannelById(threadId);
            if (thread == null) {
                // Essayer de récupérer le channel parent et le thread
                Document ticketDoc = ticketsCollection.find(Filters.eq("threadId", threadId)).first();
                if (ticketDoc != null) {
                    String parentChannelId = ticketDoc.getString("parentChannelId");
                    TextChannel parentChannel = guild.getTextChannelById(parentChannelId);
                    if (parentChannel != null) {
                        parentChannel.retrieveArchivedPublicThreadChannels()
                                .queue(threads -> {
                                    ThreadChannel targetThread = threads.stream()
                                            .filter(t -> t.getId().equals(threadId))
                                            .findFirst()
                                            .orElse(null);

                                    if (targetThread != null) {
                                        unarchiveTicket(event, targetThread);
                                    } else {
                                        parentChannel.retrieveArchivedPrivateThreadChannels()
                                                .queue(privateThreads -> {
                                                    ThreadChannel privateTargetThread = privateThreads.stream()
                                                            .filter(t -> t.getId().equals(threadId))
                                                            .findFirst()
                                                            .orElse(null);

                                                    if (privateTargetThread != null) {
                                                        unarchiveTicket(event, privateTargetThread);
                                                    } else {
                                                        event.reply("Impossible de trouver ce ticket, il a peut-être été supprimé.").setEphemeral(true).queue();
                                                    }
                                                }, error -> {
                                                    event.reply("Erreur lors de la récupération des threads privés.").setEphemeral(true).queue();
                                                    LOGGER.error("Erreur lors de la récupération des threads privés: {}", error.getMessage());
                                                });
                                    }
                                }, error -> {
                                    event.reply("Erreur lors de la récupération des threads.").setEphemeral(true).queue();
                                    LOGGER.error("Erreur lors de la récupération des threads publics: {}", error.getMessage());
                                });
                        return;
                    }
                }

                event.reply("Impossible de trouver ce ticket, il a peut-être été supprimé.").setEphemeral(true).queue();
                return;
            }

            unarchiveTicket(event, thread);
        }
    }

    /**
     * Désarchive un ticket à partir du menu de sélection
     *
     * @param event L'événement de sélection
     * @param thread Le thread à désarchiver
     */
    private void unarchiveTicket(StringSelectInteractionEvent event, ThreadChannel thread) {
        thread.getManager().setArchived(false).queue(
                success -> {
                    MessageEmbed embed = new EmbedBuilder()
                            .setTitle("Ticket désarchivé")
                            .setDescription("Ce ticket a été désarchivé. Il sera automatiquement archivé après une semaine d'inactivité.")
                            .setColor(Color.GREEN)
                            .setFooter("ID du ticket: " + thread.getId(), null)
                            .build();

                    thread.sendMessageEmbeds(embed).queue();

                    // Mettre à jour dans MongoDB
                    ticketsCollection.updateOne(
                            Filters.eq("threadId", thread.getId()),
                            Updates.set("archived", false)
                    );

                    event.reply("Le ticket a été désarchivé avec succès.").setEphemeral(true).queue();
                    LOGGER.info("Ticket désarchivé: {}", thread.getName());
                },
                error -> {
                    event.reply("Erreur lors du désarchivage du ticket: " + error.getMessage()).setEphemeral(true).queue();
                    LOGGER.error("Erreur lors du désarchivage du ticket {}: {}", thread.getName(), error.getMessage());
                }
        );
    }

    /**
     * Crée un nouveau ticket (thread) quand un utilisateur clique sur le bouton
     *
     * @param event L'événement de clic sur le bouton
     */
    private void handleCreateTicket(ButtonInteractionEvent event) {
        LOGGER.info("Début handleCreateTicket - User: {}", event.getUser().getName());

        if (event.getGuild() == null) {
            event.reply("Cette fonction ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook -> {
            try {
                // Déterminer le numéro du ticket en comptant les documents existants + 1
                long ticketCount = ticketsCollection.countDocuments() + 1;
                String threadName = "ticket-" + event.getUser().getName().toLowerCase() + "-" + ticketCount;
                LOGGER.info("Création du ticket numéro {}: {}", ticketCount, threadName);

                TextChannel parentChannel = event.getChannel().asTextChannel();

                // Création du thread
                parentChannel.createThreadChannel(threadName, true)
                        .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
                        .queue(
                                threadChannel -> {
                                    LOGGER.info("Thread créé avec succès: {}", threadChannel.getName());

                                    hook.sendMessage("Votre ticket a été créé avec succès dans " + threadChannel.getAsMention()).queue();

                                    MessageEmbed welcomeEmbed = new EmbedBuilder()
                                            .setTitle("Ticket ouvert")
                                            .setDescription("Bonjour " + event.getUser().getAsMention() + ", merci d'avoir créé un ticket. " +
                                                    "Veuillez décrire votre problème ou votre question en détail, et un membre de notre équipe vous aidera dès que possible.")
                                            .setColor(Color.GREEN)
                                            .addField("Information", "Ce ticket sera automatiquement archivé après une semaine d'inactivité. " +
                                                    "Vous pouvez manuellement archiver ce ticket en utilisant le bouton ci-dessous une fois votre problème résolu.", false)
                                            .setFooter("ID du ticket: " + threadChannel.getId() + " | Ticket #" + ticketCount, null)
                                            .build();

                                    Button archiveButton = Button.secondary(ARCHIVE_TICKET_BUTTON, "Fermer").withEmoji(Emoji.fromUnicode("📁"));

                                    threadChannel.sendMessageEmbeds(welcomeEmbed)
                                            .addActionRow(archiveButton)
                                            .queue();

                                    // Ajouter l'utilisateur qui a créé le ticket
                                    threadChannel.addThreadMember(event.getUser()).queue();

                                    // Ajouter tous les administrateurs
                                    LOGGER.info("Ajout des administrateurs au thread");
                                    event.getGuild().loadMembers().onSuccess(members -> {
                                        for (Member member : members) {
                                            if (member.hasPermission(Permission.ADMINISTRATOR) && !member.getUser().isBot()) {
                                                LOGGER.info("Ajout de l'administrateur {} au thread", member.getUser().getName());
                                                threadChannel.addThreadMember(member).queue(
                                                        success -> LOGGER.debug("Admin {} ajouté au thread", member.getUser().getName()),
                                                        error -> LOGGER.warn("Erreur ajout admin {}: {}", member.getUser().getName(), error.getMessage())
                                                );
                                            }
                                        }
                                    });

                                    // MongoDB
                                    Document ticketDoc = new Document()
                                            .append("threadId", threadChannel.getId())
                                            .append("ticketNumber", ticketCount)
                                            .append("userId", event.getUser().getId())
                                            .append("userName", event.getUser().getName())
                                            .append("parentChannelId", parentChannel.getId())
                                            .append("createdAt", Instant.now().toString())
                                            .append("archived", false);

                                    ticketsCollection.insertOne(ticketDoc);
                                    LOGGER.info("Ticket #{} enregistré dans MongoDB", ticketCount);
                                },
                                error -> {
                                    LOGGER.error("Erreur création thread: {}", error.getMessage());
                                    hook.sendMessage("Erreur lors de la création du ticket: " + error.getMessage()).queue();
                                }
                        );
            } catch (Exception e) {
                LOGGER.error("Exception lors de la création du ticket: {}", e.getMessage(), e);
                hook.sendMessage("Une erreur inattendue s'est produite.").queue();
            }
        });
    }

    /**
     * Gère l'archivage d'un ticket via le bouton
     *
     * @param event L'événement de clic sur le bouton
     */
    private void handleArchiveButton(ButtonInteractionEvent event) {
        if (event.getGuild() == null || !(event.getChannel() instanceof ThreadChannel)) {
            event.reply("Cette action n'est possible que dans un thread de ticket.").setEphemeral(true).queue();
            return;
        }

        ThreadChannel threadChannel = event.getChannel().asThreadChannel();

        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Ticket archivé")
                .setDescription("Ce ticket a été archivé. Un administrateur peut le désarchiver avec la commande `/ticket unarchive`.")
                .setColor(Color.ORANGE)
                .setFooter("ID du ticket: " + threadChannel.getId(), null)
                .build();

        threadChannel.sendMessageEmbeds(embed).queue(message -> {
            // Archiver le thread
            threadChannel.getManager().setArchived(true).queue(
                    success -> {
                        // Mettre à jour le statut dans MongoDB
                        ticketsCollection.updateOne(
                                Filters.eq("threadId", threadChannel.getId()),
                                Updates.set("archived", true)
                        );

                        event.reply("Le ticket a été archivé.").setEphemeral(true).queue();
                        LOGGER.info("Ticket archivé: {}", threadChannel.getName());
                    },
                    error -> {
                        event.reply("Erreur lors de l'archivage du ticket: " + error.getMessage()).setEphemeral(true).queue();
                        LOGGER.error("Erreur lors de l'archivage du ticket {}: {}", threadChannel.getName(), error.getMessage());
                    }
            );
        });
    }

    /**
     * Gère la commande '/ticket archive'
     *
     * @param guild La guilde
     * @param channel Le channel à archiver
     */
    public void handleArchive(MessageChannel channel) {
        if (!(channel instanceof ThreadChannel threadChannel)) {
            LOGGER.error("Tentative d'archiver un channel qui n'est pas un thread: {}", channel.getName());
            return;
        }
        if (!threadChannel.getName().startsWith("ticket-")) {
            LOGGER.error("Tentative d'archiver un thread qui n'est pas un ticket: {}", threadChannel.getName());
            return;
        }
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Ticket archivé")
                .setDescription("Ce ticket a été archivé par un administrateur. Un administrateur peut le désarchiver avec la commande `/ticket unarchive`.")
                .setColor(Color.ORANGE)
                .setFooter("ID du ticket: " + threadChannel.getId(), null)
                .build();

        threadChannel.sendMessageEmbeds(embed).queue(message -> {
            threadChannel.getManager().setArchived(true).queue(
                    success -> {
                        ticketsCollection.updateOne(
                                Filters.eq("threadId", threadChannel.getId()),
                                Updates.set("archived", true)
                        );
                        LOGGER.info("Ticket archivé par commande: {}", threadChannel.getName());
                    },
                    error -> LOGGER.error("Erreur lors de l'archivage du ticket {}: {}", threadChannel.getName(), error.getMessage())
            );
        });
    }

    /**
     * Affiche un sélecteur des tickets archivés d'un utilisateur pour désarchivage
     *
     * @param event L'événement de commande slash
     */
    public void handleUnarchiveCommand(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.getHook().sendMessage("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        // Obtenir la liste des tickets archivés
        List<Document> archivedTickets = ticketsCollection.find(Filters.eq("archived", true))
                .into(new ArrayList<>());

        if (archivedTickets.isEmpty()) {
            event.getHook().sendMessage("Aucun ticket archivé trouvé.").setEphemeral(true).queue();
            return;
        }

        // Créer le menu de sélection
        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(UNARCHIVE_SELECT_MENU)
                .setPlaceholder("Sélectionnez un ticket à désarchiver")
                .setRequiredRange(1, 1);

        for (Document ticket : archivedTickets) {
            String threadId = ticket.getString("threadId");
            String userName = ticket.getString("userName");
            String createdAt = ticket.getString("createdAt");

            // Format: Ticket de User (créé le 2025-04-10)
            String label = "Ticket de " + userName;
            String description = "Créé le " + Instant.parse(createdAt).toString().split("T")[0];

            menuBuilder.addOption(label, threadId, description);
        }

        StringSelectMenu menu = menuBuilder.build();

        // Envoyer le menu
        event.getHook().sendMessage("Veuillez sélectionner le ticket à désarchiver:")
                .addActionRow(menu)
                .setEphemeral(true)
                .queue();
    }
}