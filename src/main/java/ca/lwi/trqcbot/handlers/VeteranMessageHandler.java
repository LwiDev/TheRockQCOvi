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

public class VeteranMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(VeteranMessageHandler.class);
    private static final MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection("messages");
    private static final String TARGET_CHANNEL_ID = "1360007105175617678";

    public static CompletableFuture<Void> updateVeteranMessage(boolean forceNewMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            TextChannel channel = Main.getJda().getTextChannelById(TARGET_CHANNEL_ID);
            if (channel == null) {
                LOGGER.error("Channel with ID {} not found", TARGET_CHANNEL_ID);
                future.completeExceptionally(new IllegalArgumentException("Channel not found"));
                return future;
            }

            String messageContent = generateVeteranMessage();
            Document storedMessage = collection.find(Filters.eq("channelId", TARGET_CHANNEL_ID)).first();

            if (storedMessage != null && !forceNewMessage) {
                String messageId = storedMessage.getString("messageId");
                channel.retrieveMessageById(messageId).queue(
                        message -> {
                            message.editMessage(messageContent).queue(
                                    updated -> future.complete(null),
                                    error -> {
                                        LOGGER.error("Failed to update devenir-veteran message: {}", error.getMessage());
                                        createNewVeteranMessage(channel, messageContent, future);
                                    }
                            );
                        },
                        error -> {
                            LOGGER.error("Failed to retrieve devenir-veteran message: {}", error.getMessage());
                            createNewVeteranMessage(channel, messageContent, future);
                        }
                );
            } else {
                createNewVeteranMessage(channel, messageContent, future);
            }
        } catch (Exception e) {
            LOGGER.error("Error in updateVeteranMessage: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    private static void createNewVeteranMessage(TextChannel channel, String messageContent, CompletableFuture<Void> future) {
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
                    LOGGER.info("New devenir-veteran message created in channel {}", channel.getId());
                },
                error -> {
                    LOGGER.error("Failed to send devenir-veteran message: {}", error.getMessage());
                    future.completeExceptionally(error);
                }
        );
    }

    private static String generateVeteranMessage() {
        return """
                # 📌 Comment devenir VÉTÉRAN ?
                
                Pour obtenir le rôle **Vétéran** et accéder aux contenus exclusifs réservés aux soutiens YouTube, suis ces étapes :
                
                1. Ouvre tes paramètres Discord (⚙️ en bas à gauche).
                2. Va dans l’onglet **"Connexions"**.
                3. Clique sur l’icône **YouTube** et connecte ton compte.
                4. Une fois connecté, assure-toi que :
                   - Ton **abonnement payant** est actif sur la chaîne YouTube.
                   - Tu es bien membre de **ce serveur Discord**.
                
                🕒 *Le rôle est attribué automatiquement en quelques minutes. Si tu ne le reçois pas, patiente un peu ou rends-toi dans <#1357158503541641266> pour ouvrir un ticket.*
                
                🎖️ **Pas encore Vétéran ?**
                Soutiens **TheRockQC** et débloque du contenu exclusif ici → https://www.youtube.com/@TheRockQC/join""";
    }
}