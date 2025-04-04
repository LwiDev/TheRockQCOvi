package src.ca.lwi.trqcbot.handlers;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import src.ca.lwi.trqcbot.Main;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ResourcesEmbedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourcesEmbedHandler.class);
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
     * Updates the resources embed in the specified channel
     * @param forceNewMessage If true, creates a new message even if one exists
     * @return CompletableFuture that completes when operation is done
     */
    public static CompletableFuture<Void> updateWelcomeEmbed(boolean forceNewMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            TextChannel channel = Main.getJda().getTextChannelById(TARGET_CHANNEL_ID);
            if (channel == null) {
                LOGGER.error("Welcome channel with ID {} not found", TARGET_CHANNEL_ID);
                future.completeExceptionally(new IllegalArgumentException("Channel not found"));
                return future;
            }

            MessageEmbed embed = generateWelcomeEmbed();
            Document storedMessage = collection.find(Filters.eq("channelId", TARGET_CHANNEL_ID)).first();

            if (storedMessage != null && !forceNewMessage) {
                String messageId = storedMessage.getString("messageId");
                channel.retrieveMessageById(messageId).queue(
                        message -> {
                            message.editMessageEmbeds(embed).queue(
                                    updated -> future.complete(null),
                                    error -> {
                                        LOGGER.error("Failed to update resources embed: {}", error.getMessage());
                                        // If editing fails, create a new message
                                        createNewWelcomeEmbed(channel, embed, future);
                                    }
                            );
                        },
                        error -> {
                            LOGGER.error("Failed to retrieve resources embed message: {}", error.getMessage());
                            // Message might have been deleted, create a new one
                            createNewWelcomeEmbed(channel, embed, future);
                        }
                );
            } else {
                // Create a new message
                createNewWelcomeEmbed(channel, embed, future);
            }
        } catch (Exception e) {
            LOGGER.error("Error in updateWelcomeEmbed: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Creates a new resources embed message in the channel
     */
    private static void createNewWelcomeEmbed(TextChannel channel, MessageEmbed embed, CompletableFuture<Void> future) {
        channel.sendMessageEmbeds(embed).queue(
                message -> {
                    Document doc = new Document()
                            .append("channelId", channel.getId())
                            .append("messageId", message.getId())
                            .append("lastUpdated", System.currentTimeMillis());
                    collection.updateOne(
                            Filters.eq("channelId", channel.getId()),
                            new Document("$set", doc),
                            new UpdateOptions().upsert(true)
                    );
                    future.complete(null);
                    LOGGER.info("New resources embed created in channel {}", channel.getId());
                },
                error -> {
                    LOGGER.error("Failed to send resources embed: {}", error.getMessage());
                    future.completeExceptionally(error);
                }
        );
    }

    /**
     * Generates the resources embed using data from MongoDB
     */
    private static MessageEmbed generateWelcomeEmbed() {
        Document configDoc = getOrCreateConfigDocument();
        String serverName = configDoc.getString(KEY_SERVER_NAME);

        List<Document> roles = configDoc.getList(KEY_ROLES, Document.class, new ArrayList<>());
        List<Document> categories = configDoc.getList(KEY_CATEGORIES, Document.class, new ArrayList<>());
        List<Document> importantChannels = configDoc.getList(KEY_IMPORTANT_CHANNELS, Document.class, new ArrayList<>());
        List<Document> usefulLinks = configDoc.getList(KEY_USEFUL_LINKS, Document.class, new ArrayList<>());

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📚 Bienvenue sur le serveur de " + serverName + " !");
        embed.setDescription("Ici, tu peux discuter de NHL 25, suivre les vidéos du créateur et interagir avec la " +
                "communauté. Avant de commencer, voici quelques infos essentielles !");
        embed.setColor(new Color(178, 34, 34));
        embed.setTimestamp(Instant.now());

        // Caractère d'espacement pour créer un léger padding
        String padding = "\u200B \u200B";

        // Add categories section
        if (!categories.isEmpty()) {
            StringBuilder categoryContent = new StringBuilder();
            for (Document category : categories) {
                String emoji = category.getString("emoji");
                String name = category.getString("name");
                String description = category.getString("description");
                categoryContent.append("• ").append(emoji).append(" **").append(name).append("** → ").append(description).append("\n");
            }
            embed.addField("📂 Les catégories", categoryContent.toString(), false);
        }

        embed.addField(padding, "", false);

        // Add roles section
        if (!roles.isEmpty()) {
            StringBuilder roleContent = new StringBuilder();
            for (Document role : roles) {
                String emoji = role.getString("emoji");
                String name = role.getString("name");
                String description = role.getString("description");
                roleContent.append(emoji).append(" **").append(name).append("** → ").append(description).append("\n");
            }
            embed.addField("👥 Les rôles", roleContent.toString(), false);
        }

        // Léger espacement entre les sections
        embed.addField(padding, "", false);

        // Add important channels section
        if (!importantChannels.isEmpty()) {
            StringBuilder channelContent = new StringBuilder();
            for (Document channel : importantChannels) {
                String emoji = channel.getString("emoji");
                String name = channel.getString("name");
                String description = channel.getString("description");
                channelContent.append(emoji).append(" **").append(name).append("** → ").append(description).append("\n");
            }
            embed.addField("📌 Salons importants", channelContent.toString(), false);
        }

        // Léger espacement entre les sections
        embed.addField(padding, "", false);

        // Add useful links section with proper pluralization
        if (!usefulLinks.isEmpty()) {
            StringBuilder linkContent = new StringBuilder();
            for (Document link : usefulLinks) {
                String emoji = link.getString("emoji");
                String name = link.getString("name");
                String url = link.getString("url");
                linkContent.append(emoji).append(" [").append(name).append("](").append(url).append(")\n");
            }
            String linkTitle = usefulLinks.size() > 1 ? "🔗 Liens utiles" : "🔗 Lien utile";
            embed.addField(linkTitle, linkContent.toString(), false);
        }

        // Add footer with update timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy à HH:mm");
        embed.setFooter("Mis à jour le " + sdf.format(new Date()), null);

        return embed.build();
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
     * Updates a specific section of the resources embed
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

            // Update the embed after section change
            return updateWelcomeEmbed(false);
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

            // Update the embed after section change
            return updateWelcomeEmbed(false);
        } catch (Exception e) {
            LOGGER.error("Error updating server name: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    // Default data creators for initial setup

    private static List<Document> createDefaultRoles() {
        List<Document> roles = new ArrayList<>();

        roles.add(new Document()
                .append("emoji", "🟢")
                .append("name", "Recrue")
                .append("description", "Nouveau membre, accès limité au serveur."));

        roles.add(new Document()
                .append("emoji", "🟢")
                .append("name", "Joueur")
                .append("description", "Membre régulier, accès aux discussions."));

        roles.add(new Document()
                .append("emoji", "🟢")
                .append("name", "Entraîneur")
                .append("description", "Modérateur du serveur."));

        roles.add(new Document()
                .append("emoji", "🟠")
                .append("name", "Vétéran")
                .append("description", "Abonné YouTube premium avec des avantages exclusifs."));

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
                .append("name", "Vestiaire")
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
                .append("emoji", "📢")
                .append("name", "#annonces")
                .append("description", "Infos importantes sur le serveur."));

        channels.add(new Document()
                .append("emoji", "📺")
                .append("name", "#vidéos")
                .append("description", "Nouvelles vidéos YouTube et lives."));

        channels.add(new Document()
                .append("emoji", "📚")
                .append("name", "#ressources")
                .append("description", "Ce salon, pour toutes les infos utiles !"));

        channels.add(new Document()
                .append("emoji", "🏆")
                .append("name", "#hall-of-fame")
                .append("description", "Met en avant les plus grands exploits des membres."));

        channels.add(new Document()
                .append("emoji", "🎮")
                .append("name", "#moments-forts")
                .append("description", "Partage des clips ou des actions mémorables du jeu, de la NHL ou de TheRock."));

        channels.add(new Document()
                .append("emoji", "💡")
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
