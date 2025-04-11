package ca.lwi.trqcbot.youtube;

import ca.lwi.trqcbot.Main;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoCollection;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

public class YouTubeWatcher {

    private final String discordChannelExclusives;
    private final String discordChannelVideos;
    private final String veteranRoleId;
    private final String videoRoleId;
    private final String youtubeChannelId;
    private final String youtubeApiKey;

    private final MongoCollection<Document> collection;

    public YouTubeWatcher() {
        Dotenv dotenv = Dotenv.load();
        this.discordChannelExclusives = dotenv.get("CHANNEL_EXCLUSIVES_ID");
        this.discordChannelVideos = dotenv.get("CHANNEL_VIDEOS_ID");
        this.videoRoleId = dotenv.get("VIDEO_ROLE_ID");
        this.veteranRoleId = dotenv.get("VETERAN_ROLE_ID");
        this.youtubeChannelId = dotenv.get("YOUTUBE_CHANNEL_ID");
        this.youtubeApiKey = dotenv.get("YOUTUBE_API_KEY");

        this.collection = Main.getMongoConnection().getDatabase().getCollection("data_history");
    }

    public void start(JDA jda) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                try {
                    JsonObject data = getJsonObject();
                    JsonArray items = data.getAsJsonArray("items");

                    if (!items.isEmpty()) {
                        JsonObject video = items.get(0).getAsJsonObject();
                        String videoId = video.getAsJsonObject("id").get("videoId").getAsString();

                        String lastVideoId = getLastVideoId();
                        if (!videoId.equals(lastVideoId)) {
                            setLastVideoId(videoId);

                            String title = video.getAsJsonObject("snippet").get("title").getAsString();
                            String urlVideo = "https://www.youtube.com/watch?v=" + videoId;

                            // Vérification si c'est une vidéo "members only"
                            if (isMembersOnly(title)) {
                                // Envoi dans le salon pour membres payants
                                TextChannel salonExclusives = jda.getTextChannelById(discordChannelExclusives);
                                if (salonExclusives != null) {
                                    EmbedBuilder embed = new EmbedBuilder();
                                    embed.setTitle(title, urlVideo);
                                    embed.setDescription(":star: **Vidéo exclusive pour les membres !**");
                                    embed.setColor(Color.decode("#0055A4"));
                                    embed.setFooter("TheRockQC - du contenu premium!");

                                    salonExclusives.sendMessage("<@&" + veteranRoleId + ">").setEmbeds(embed.build()).queue();
                                }
                            } else {
                                // Envoi dans le salon public (vidéo normale)
                                TextChannel salonVideos = jda.getTextChannelById(discordChannelVideos);
                                if (salonVideos != null) {
                                    EmbedBuilder embed = new EmbedBuilder();
                                    embed.setTitle(title, urlVideo);
                                    embed.setDescription(":hockey: **Nouvelle vidéo !** :goal_net:");
                                    embed.setColor(Color.decode("#0055A4"));
                                    embed.setFooter("TheRockQC - du contenu en power play!");

                                    salonVideos.sendMessage("<@&" + videoRoleId + ">").setEmbeds(embed.build()).queue();
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 5 * 60 * 1000);
    }

    @NotNull
    private JsonObject getJsonObject() throws IOException, URISyntaxException {
        String url = "https://www.googleapis.com/youtube/v3/search?order=date&part=snippet&channelId=" + youtubeChannelId + "&maxResults=1&type=video&key=" + youtubeApiKey;
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.connect();

        Scanner scanner = new Scanner(conn.getInputStream());
        StringBuilder inline = new StringBuilder();
        while (scanner.hasNext()) {
            inline.append(scanner.nextLine());
        }
        scanner.close();

        // Utilisation de JsonParser pour créer un JsonObject à partir d'une chaîne
        return JsonParser.parseString(inline.toString()).getAsJsonObject();
    }

    private String getLastVideoId() {
        Document doc = collection.find(new Document("channelId", youtubeChannelId)).first();
        return doc != null ? doc.getString("lastVideoId") : "";
    }

    private void setLastVideoId(String videoId) {
        collection.updateOne(
                new Document("channelId", youtubeChannelId),
                new Document("$set", new Document("lastVideoId", videoId)),
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        );
    }

    private boolean isMembersOnly(String title) {
        return title.toLowerCase().contains("members only") || title.toLowerCase().contains("membres");
    }
}