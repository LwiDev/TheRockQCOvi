package ca.lwi.trqcbot.ranks;

import ca.lwi.trqcbot.Main;
import com.google.api.services.youtube.YouTube;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RankManager extends ListenerAdapter {

    private static RankManager instance;
    private final MongoCollection<Document> userCollection;
    private final String guildId;
    private final String recrueRoleId;
    private final String joueurRoleId;
    private final String veteranRoleId;
    private final String youtubeChannelId;
    private final String youtubeApiKey;
    private final Map<String, Integer> userActivityCounter = new HashMap<>();
    private final int activityThreshold;

    private YouTube youtubeService;
    private final String youtubeVerificationChannelId;

    public RankManager() {
        // Chargement des variables d'environnement avec dotenv
        Dotenv dotenv = Dotenv.load();
        this.guildId = dotenv.get("GUILD_ID");
        this.recrueRoleId = dotenv.get("RECRUE_ROLE_ID");
        this.joueurRoleId = dotenv.get("JOUEUR_ROLE_ID");
        this.veteranRoleId = dotenv.get("VETERAN_ROLE_ID");
        this.youtubeChannelId = dotenv.get("YOUTUBE_CHANNEL_ID");
        this.youtubeApiKey = dotenv.get("YOUTUBE_API_KEY");
        this.youtubeVerificationChannelId = dotenv.get("YOUTUBE_VERIFICATION_CHANNEL_ID");
        this.activityThreshold = Integer.parseInt(dotenv.get("ACTIVITY_THRESHOLD"));
        this.userCollection = Main.getMongoConnection().getDatabase().getCollection("users");

        // Planifier une vérification périodique des activités
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::checkUsersActivity, 1, 12, TimeUnit.HOURS);
    }
    
    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent e) {
        Guild guild = e.getGuild();
        Member member = e.getMember();
        Document existingUser = userCollection.find(new Document("userId", member.getId())).first();
        if (existingUser != null) return;

        Document userDoc = new Document("userId", member.getId())
                .append("username", member.getUser().getName())
                .append("joinDate", new Date(System.currentTimeMillis()))
                .append("messageCount", 0)
                .append("youtubeLinked", false)
                .append("currentRank", "Recrue");
        userCollection.insertOne(userDoc);
        
        Role recrueRole = guild.getRoleById(recrueRoleId);
        if (recrueRole != null) guild.addRoleToMember(UserSnowflake.fromId(member.getId()), recrueRole).queue();

        Main.getWelcomeMessageHandler().createMessage(e.getGuild(), e.getMember());
    }
    
    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        String userId = event.getAuthor().getId();
        userActivityCounter.put(userId, userActivityCounter.getOrDefault(userId, 0) + 1);
        if (userActivityCounter.getOrDefault(userId, 0) % 5 == 0) {
            incrementUserActivity(userId);
        }
    }
    
    public void incrementUserActivity(String userId) {
        // Incrémenter le compteur de messages dans MongoDB
        Document user = userCollection.find(new Document("userId", userId)).first();
        if (user != null) {
            int currentCount = user.getInteger("messageCount", 0);
            userCollection.updateOne(
                new Document("userId", userId),
                new Document("$set", new Document("messageCount", currentCount + 5))
            );
            
            // Vérifier si l'utilisateur doit passer de Recrue à Joueur
            if (currentCount + 5 >= activityThreshold && "Recrue".equals(user.getString("currentRank"))) {
                promoteToJoueur(userId);
            }
        }
    }
    
    public void promoteToJoueur(String userId) {
        Guild guild = Main.getJda().getGuildById(guildId);
        if (guild == null) return;
        
        // Mettre à jour le rang dans MongoDB
        userCollection.updateOne(
            new Document("userId", userId),
            new Document("$set", new Document("currentRank", "Joueur"))
        );
        
        // Attribuer le rôle Joueur et retirer le rôle Recrue
        Member member = guild.retrieveMemberById(userId).complete();
        if (member != null) {
            Role joueurRole = guild.getRoleById(joueurRoleId);
            Role recrueRole = guild.getRoleById(recrueRoleId);
            
            if (joueurRole != null) {
                guild.addRoleToMember(UserSnowflake.fromId(userId), joueurRole).queue();
            }
            if (recrueRole != null) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), recrueRole).queue();
            }
            
            // Notifier l'utilisateur
            member.getUser().openPrivateChannel().queue(
                channel -> channel.sendMessage("Félicitations ! Vous êtes maintenant un Joueur grâce à votre activité sur le serveur.").queue()
            );
        }
    }
    
    public void linkYoutubeAccount(String userId, String youtubeUsername) {
        // Mettre à jour les infos YouTube dans MongoDB
        userCollection.updateOne(
            new Document("userId", userId),
            new Document("$set", new Document("youtubeLinked", true)
                                       .append("youtubeUsername", youtubeUsername))
        );
    }
    
    public boolean checkYoutubeSubscription(String youtubeUsername) {
        // Dans un environnement réel, vous devriez implémenter la vérification via l'API YouTube OAuth
        // Cette méthode est simplifiée et simule une vérification
        
        // Pour l'exemple, nous retournons un résultat aléatoire
        // Dans une vraie implémentation, vous interrogeriez l'API YouTube
        return Math.random() > 0.5;
    }
    
    public void promoteToVeteran(String userId) {
        Guild guild = Main.getJda().getGuildById(guildId);
        if (guild == null) return;
        
        // Mettre à jour le rang dans MongoDB
        userCollection.updateOne(
            new Document("userId", userId),
            new Document("$set", new Document("currentRank", "Vétéran"))
        );
        
        // Attribuer le rôle Vétéran et retirer les autres rôles
        Member member = guild.retrieveMemberById(userId).complete();
        if (member != null) {
            Role veteranRole = guild.getRoleById(veteranRoleId);
            Role joueurRole = guild.getRoleById(joueurRoleId);
            Role recrueRole = guild.getRoleById(recrueRoleId);
            
            if (veteranRole != null) {
                guild.addRoleToMember(UserSnowflake.fromId(userId), veteranRole).queue();
            }
            
            // Retirer les rôles inférieurs
            if (joueurRole != null && member.getRoles().contains(joueurRole)) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), joueurRole).queue();
            }
            if (recrueRole != null && member.getRoles().contains(recrueRole)) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), recrueRole).queue();
            }
            
            // Notifier l'utilisateur
            member.getUser().openPrivateChannel().queue(
                channel -> channel.sendMessage("Félicitations ! Vous êtes maintenant un Vétéran grâce à votre abonnement à TheRockQC.").queue()
            );
        }
    }
    
    public Document getUserData(String userId) {
        return userCollection.find(new Document("userId", userId)).first();
    }
    
    private void checkUsersActivity() {
        Guild guild = Main.getJda().getGuildById(guildId);
        if (guild == null) return;
        
        for (Document user : userCollection.find()) {
            try {
                String userId = user.getString("userId");
                int messageCount = user.getInteger("messageCount", 0);
                String currentRank = user.getString("currentRank");
                
                // Vérifier si l'utilisateur doit être promu en fonction de son activité
                if ("Recrue".equals(currentRank) && messageCount >= activityThreshold) {
                    promoteToJoueur(userId);
                }
            } catch (Exception e) {
                System.err.println("Erreur lors de la vérification de l'activité: " + e.getMessage());
            }
        }
    }

    // Only for test --- TODO : Remove after
    // Ajoutez ces méthodes à votre classe RankManager existante

    /**
     * Met à jour le rang d'un utilisateur dans la base de données
     * @param userId ID de l'utilisateur
     * @param rank Le nouveau rang (Recrue, Joueur, Vétéran)
     */
    public void updateUserRank(String userId, String rank) {
        // Vérifier si l'utilisateur existe déjà
        Document existingUser = userCollection.find(new Document("userId", userId)).first();

        if (existingUser != null) {
            // Mettre à jour le rang de l'utilisateur existant
            userCollection.updateOne(
                    new Document("userId", userId),
                    new Document("$set", new Document("currentRank", rank))
            );
        } else {
            // Créer un nouvel utilisateur avec le rang spécifié
            Document userDoc = new Document("userId", userId)
                    .append("username", "Utilisateur " + userId)  // Nom temporaire, sera mis à jour plus tard
                    .append("joinDate", System.currentTimeMillis())
                    .append("messageCount", 0)
                    .append("youtubeLinked", false)
                    .append("youtubeUsername", "")
                    .append("currentRank", rank);

            userCollection.insertOne(userDoc);
        }
    }

    /**
     * Enregistre un utilisateur existant dans la base de données avec son rang actuel
     * @param userId ID de l'utilisateur
     * @param username Nom d'utilisateur
     * @param rank Rang actuel (Joueur ou Vétéran)
     */
    public void registerExistingUser(String userId, String username, String rank) {
        // Préparation du document utilisateur
        Document userDoc = new Document("userId", userId)
                .append("username", username)
                .append("joinDate", System.currentTimeMillis())  // Date actuelle comme date d'arrivée
                .append("messageCount", 0)  // Commencer avec 0 messages
                .append("youtubeLinked", false)
                .append("youtubeUsername", "")
                .append("currentRank", rank);

        // Cette option permet d'insérer un nouveau document si l'utilisateur n'existe pas,
        // ou de mettre à jour un document existant
        UpdateOptions options = new UpdateOptions().upsert(true);

        // Upsert pour mettre à jour si l'utilisateur existe déjà, sinon l'insérer
        userCollection.updateOne(
                Filters.eq("userId", userId),
                new Document("$set", userDoc),
                options
        );
    }
}