package ca.lwi.trqcbot.handlers;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class RulesMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RulesMessageHandler.class);
    private static final MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection("messages");
    private static final String TARGET_CHANNEL_ID = "1356751338427650121";

    public static CompletableFuture<Void> updateRulesMessage(boolean forceNewMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            TextChannel channel = Main.getJda().getTextChannelById(TARGET_CHANNEL_ID);
            if (channel == null) {
                LOGGER.error("Channel with ID {} not found", TARGET_CHANNEL_ID);
                future.completeExceptionally(new IllegalArgumentException("Channel not found"));
                return future;
            }

            String messageContent = generateRulesMessage();
            Document storedMessage = collection.find(Filters.eq("channelId", TARGET_CHANNEL_ID)).first();

            if (storedMessage != null && !forceNewMessage) {
                String messageId = storedMessage.getString("messageId");
                channel.retrieveMessageById(messageId).queue(
                        message -> {
                            message.editMessage(messageContent).queue(
                                    updated -> future.complete(null),
                                    error -> {
                                        LOGGER.error("Failed to update rules message: {}", error.getMessage());
                                        createNewRulesMessage(channel, messageContent, future);
                                    }
                            );
                        },
                        error -> {
                            LOGGER.error("Failed to retrieve rules message: {}", error.getMessage());
                            createNewRulesMessage(channel, messageContent, future);
                        }
                );
            } else {
                createNewRulesMessage(channel, messageContent, future);
            }
        } catch (Exception e) {
            LOGGER.error("Error in updateRulesMessage: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    private static void createNewRulesMessage(TextChannel channel, String messageContent, CompletableFuture<Void> future) {
        channel.sendMessage(messageContent).queue(
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
                    LOGGER.info("New rules message created in channel {}", channel.getId());
                },
                error -> {
                    LOGGER.error("Failed to send rules message: {}", error.getMessage());
                    future.completeExceptionally(error);
                }
        );
    }

    private static String generateRulesMessage() {
        return """
                # 📜 Règlement du Serveur
                
                Bienvenue sur le serveur ! Pour que tout se passe bien, voici quelques règles à respecter. Toute infraction peut mener à des sanctions.
                
                ## 1️⃣ Respect & Comportement
                🔹 Respecte tout le monde – pas d'insultes, harcèlement ou discrimination.
                🔹 Aucune provocation ou comportement toxique.
                🔹 Aucune discussion sur des sujets sensibles (politique, religion, etc.).
                
                ## 2️⃣ Contenu & Messages
                🔹 Pas de spam, flood ou abus de majuscules.
                🔹 Aucune pub ou lien non autorisé.
                🔹 Aucun contenu NSFW, illégal ou inapproprié.
                
                ## 3️⃣ Salons & Discussions
                🔹 Poste dans le bon salon et utilise les bons tags pour les suggestions.
                🔹 Les débats doivent rester sains et respectueux.
                🔹 Pas d’abus des mentions (everyone, here…).
                
                ## 4️⃣ Système de Modération
                🔹 Les décisions des modérateurs doivent être respectées.
                🔹 Si tu as un problème avec une sanction, contacte un entraîneur en privé.
                🔹 L’abus du système de support entraînera des sanctions.
                
                ## 5️⃣ Règles Spécifiques aux Vétérans
                🔹 L’accès aux salons privés est un privilège, pas un droit.
                🔹 Le partage de contenu exclusif en dehors du serveur est interdit.
                
                📌 **Non-respect des règles = avertissements, mute, kick ou ban selon la gravité.**
                """;
    }
}