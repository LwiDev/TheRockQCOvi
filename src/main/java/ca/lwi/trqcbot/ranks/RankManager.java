package ca.lwi.trqcbot.ranks;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.reputation.ReputationManager;
import ca.lwi.trqcbot.reputation.VoiceActivityTracker;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.*;
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
    private final Map<String, Integer> userActivityCounter = new HashMap<>();
    private final int activityThreshold;

    private final VoiceActivityTracker voiceActivityTracker;
    private final ScheduledExecutorService scheduler;

    public RankManager() {
        // Chargement des variables d'environnement avec dotenv
        Dotenv dotenv = Dotenv.load();
        this.guildId = dotenv.get("GUILD_ID");
        this.recrueRoleId = dotenv.get("RECRUE_ROLE_ID");
        this.joueurRoleId = dotenv.get("JOUEUR_ROLE_ID");
        this.veteranRoleId = dotenv.get("VETERAN_ROLE_ID");
        this.activityThreshold = Integer.parseInt(dotenv.get("ACTIVITY_THRESHOLD"));
        this.userCollection = Main.getMongoConnection().getDatabase().getCollection("users");
        this.voiceActivityTracker = new VoiceActivityTracker();
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduleActivityChecks();
    }

    private void scheduleActivityChecks() {
        // Vérification des utilisateurs toutes les 12 heures
        scheduler.scheduleAtFixedRate(this::checkUsersActivity, 1, 12, TimeUnit.HOURS);

        // Vérification des utilisateurs actuellement en vocal toutes les 30 minutes
        scheduler.scheduleAtFixedRate(this::checkActiveVoiceUsers, 5, 30, TimeUnit.MINUTES);
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                // Attendre la fin des tâches en cours (avec timeout)
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent e) {
        Guild guild = e.getGuild();
        Member member = e.getMember();
        Document existingUser = userCollection.find(new Document("userId", member.getId())).first();
        if (existingUser != null) return;

        Document reputationDoc = new Document("reputationScore", 0)
                .append("messagesCount", 0)
                .append("dailyMessagesCount", 0)
                .append("avgDailyMessages", 0)
                .append("activeDaysCount", 0)
                .append("responsesCount", 0)
                .append("tagsCount", 0)
                .append("totalVoiceMinutes", 0)
                .append("dailyVoiceMinutes", 0)
                .append("voiceDaysActive", 0);

        Document userDoc = new Document("userId", member.getId())
                .append("username", member.getUser().getName())
                .append("joinDate", new Date(System.currentTimeMillis()))
                .append("currentRank", "Recrue")
                .append("reputation", reputationDoc);
        userCollection.insertOne(userDoc);

        Role recrueRole = guild.getRoleById(recrueRoleId);
        if (recrueRole != null) {
            guild.addRoleToMember(UserSnowflake.fromId(member.getId()), recrueRole).queue();
        } else {
            System.out.println("recrueRole is null");
        }

        Main.getWelcomeMessageHandler().createMessage(e.getGuild(), e.getMember());
    }
    
    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent e) {
        if (e.getAuthor().isBot()) return;
        String userId = e.getAuthor().getId();
        userActivityCounter.put(userId, userActivityCounter.getOrDefault(userId, 0) + 1);
        if (userActivityCounter.getOrDefault(userId, 0) % 5 == 0) {
            updateChatActivity(e, userId);
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent e) {
        Member member = e.getMember();
        AudioChannel oldChannel = e.getOldValue();
        AudioChannel newChannel = e.getNewValue();
        String userId = member.getId();
        this.voiceActivityTracker.trackVoiceActivity(member, oldChannel, newChannel);
        if ((oldChannel != null && newChannel == null) || (oldChannel != null && (member.getVoiceState() != null && !member.getVoiceState().isSelfMuted() && member.getVoiceState().isSelfMuted()))) {
            int minutesSpent = voiceActivityTracker.getAndResetVoiceTime(userId);
            if (minutesSpent >= 5) updateVoiceActivity(userId, minutesSpent);
        }
    }

    public void updateChatActivity(MessageReceivedEvent event, String userId) {
        Document user = userCollection.find(new Document("userId", userId)).first();
        if (user != null) {
            Message message = event.getMessage();
            String currentRank = user.getString("currentRank");

            Document reputation = (Document) user.get("reputation");
            if (reputation == null) reputation = new Document();

            int messagesCount = reputation.getInteger("messagesCount", 0);
            int responsesCount = reputation.getInteger("responsesCount", 0);
            int tagsCount = reputation.getInteger("tagsCount", 0);

            List<User> mentionedUsers = message.getMentions().getUsers();
            if (mentionedUsers.stream().map(ISnowflake::getId).anyMatch(id -> !id.equals(userId) && id.equals(user.getString("userId")))) tagsCount++;

            Message referencedMessage = message.getReferencedMessage();
            if ((message.getContentRaw().contains("@") && !mentionedUsers.isEmpty()) || (referencedMessage != null && !referencedMessage.getAuthor().getId().equals(userId))) responsesCount++;

            // Gestion des messages quotidiens
            long currentTimestamp = System.currentTimeMillis();
            long dayStart = currentTimestamp - (currentTimestamp % (1000 * 60 * 60 * 24));
            long lastMessageDay = reputation.getLong("lastMessageDay") != null ? reputation.getLong("lastMessageDay") : 0;
            int dailyMessagesCount = reputation.getInteger("dailyMessagesCount", 0);
            int avgDailyMessages = reputation.getInteger("avgDailyMessages", 0);
            int activeDaysCount = reputation.getInteger("activeDaysCount", 0);

            // Nouveau jour = mise à jour de la moyenne et réinitialisation du compteur
            if (lastMessageDay < dayStart && lastMessageDay > 0) {
                // Mettre à jour la moyenne (moyenne historique avec une pondération plus forte pour les données récentes)
                avgDailyMessages = (int) Math.round((avgDailyMessages * activeDaysCount + dailyMessagesCount) / (activeDaysCount + 1.0));
                activeDaysCount++;
                dailyMessagesCount = 1;  // Réinitialiser pour le nouveau jour
            } else if (lastMessageDay == 0) {
                // Premier message de l'utilisateur
                avgDailyMessages = 1;
                activeDaysCount = 1;
                dailyMessagesCount = 1;
            } else {
                // Même jour, incrémenter le compteur
                dailyMessagesCount++;
            }

            // Mettre à jour le sous-document reputation
            Document updatedReputation = new Document("messagesCount", messagesCount + 5)
                    .append("tagsCount", tagsCount)
                    .append("responsesCount", responsesCount)
                    .append("lastMessageDay", dayStart)
                    .append("dailyMessagesCount", dailyMessagesCount)
                    .append("avgDailyMessages", avgDailyMessages)
                    .append("activeDaysCount", activeDaysCount)
                    .append("lastActive", currentTimestamp);

            // Calculer et ajouter le score de réputation
            int reputationScore = ReputationManager.calculateReputation(user);
            updatedReputation.append("reputationScore", reputationScore);

            userCollection.updateOne(
                    new Document("userId", userId),
                    new Document("$set", new Document("reputation", updatedReputation))
            );

            if (messagesCount >= activityThreshold && currentRank.equals("Recrue")) promoteToJoueur(userId);
        }
    }

    public void updateVoiceActivity(String userId, int minutesSpent) {
        Document user = userCollection.find(new Document("userId", userId)).first();
        if (user == null) return;

        Document reputation = (Document) user.get("reputation");
        if (reputation == null) reputation = new Document();

        // Récupérer les statistiques vocales existantes ou initialiser
        int totalVoiceMinutes = reputation.getInteger("totalVoiceMinutes", 0) + minutesSpent;
        int dailyVoiceMinutes = reputation.getInteger("dailyVoiceMinutes", 0) + minutesSpent;
        int voiceDaysActive = reputation.getInteger("voiceDaysActive", 0);

        long dayStart = System.currentTimeMillis() - (System.currentTimeMillis() % (1000 * 60 * 60 * 24));
        long lastVoiceDay = reputation.getLong("lastVoiceDay") != null ? reputation.getLong("lastVoiceDay") : 0;

        // Nouveau jour d'activité vocale
        if (lastVoiceDay < dayStart) {
            dailyVoiceMinutes = minutesSpent;
            voiceDaysActive++;
        }

        // Mise à jour des statistiques vocales
        Document updatedReputation = new Document(reputation);
        updatedReputation.put("totalVoiceMinutes", totalVoiceMinutes);
        updatedReputation.put("dailyVoiceMinutes", dailyVoiceMinutes);
        updatedReputation.put("voiceDaysActive", voiceDaysActive);
        updatedReputation.put("lastVoiceDay", dayStart);
        updatedReputation.put("lastActive", System.currentTimeMillis());

        // Recalculer la réputation
        int reputationScore = ReputationManager.calculateReputation(user);
        updatedReputation.put("reputationScore", reputationScore);

        userCollection.updateOne(
                new Document("userId", userId),
                new Document("$set", new Document("reputation", updatedReputation))
        );
    }
    
    public void promoteToJoueur(String userId) {
        Guild guild = Main.getJda().getGuildById(guildId);
        if (guild == null) return;

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
    
    private void checkUsersActivity() {
        Guild guild = Main.getJda().getGuildById(guildId);
        if (guild == null) return;
        
        for (Document user : userCollection.find()) {
            try {
                String userId = user.getString("userId");
                int messagesCount = user.getInteger("messagesCount", 0);
                String currentRank = user.getString("currentRank");
                if ("Recrue".equals(currentRank) && messagesCount >= activityThreshold) promoteToJoueur(userId);
            } catch (Exception e) {
                System.err.println("Erreur lors de la vérification de l'activité: " + e.getMessage());
            }
        }
    }

    // Méthode pour vérifier les utilisateurs actuellement en vocal
    private void checkActiveVoiceUsers() {
        JDA jda = Main.getJda();
        if (jda == null) return;

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;

        Set<String> activeUsers = voiceActivityTracker.getActiveUsers();
        for (String userId : activeUsers) {
            Member member = guild.getMemberById(userId);
            if (member == null) continue;

            // Vérifier si l'utilisateur est toujours dans un canal vocal et n'est pas muet
            if (member.getVoiceState() != null && member.getVoiceState().inAudioChannel() && !member.getVoiceState().isSelfMuted() && !member.getVoiceState().isGuildMuted()) {
                int minutesSpent = voiceActivityTracker.getAndResetVoiceTime(userId);
                if (minutesSpent > 0) updateVoiceActivity(userId, minutesSpent);

                // Réinitialiser le tracker pour continuer le suivi
                VoiceChannel currentChannel = (VoiceChannel) member.getVoiceState().getChannel();
                voiceActivityTracker.trackVoiceActivity(member, null, currentChannel);
            }
        }
    }

    /**
     * Récupère les données d'un utilisateur
     * @return Une liste contenant le document de l'utilisateur
     */
    public Document getUserData(String userId) {
        return userCollection.find(new Document("userId", userId)).first();
    }

    /**
     * Récupère les données de tous les utilisateurs avec optimisation
     * @return Une liste contenant les documents des utilisateurs
     */
    public List<Document> getAllUsersData() {
        return getAllUsersData(0);
    }

    /**
     * Récupère les données de tous les utilisateurs avec optimisation
     * @param limit Nombre maximum d'utilisateurs à récupérer (0 = tous)
     * @return Une liste contenant les documents des utilisateurs
     */
    public List<Document> getAllUsersData(int limit) {
        List<Document> users = new ArrayList<>();
        Document projection = new Document("_id", 1).append("userId", 1).append("teamName", 1).append("currentRank", 1).append("reputation", 1);
        FindIterable<Document> iterable = userCollection.find().projection(projection);
        if (limit > 0) iterable = iterable.limit(limit);
        iterable.into(users);
        return users;
    }
}