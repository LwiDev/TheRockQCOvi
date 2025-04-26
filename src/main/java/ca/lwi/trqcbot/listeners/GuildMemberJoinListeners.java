package ca.lwi.trqcbot.listeners;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.Date;

public class GuildMemberJoinListeners extends ListenerAdapter {

    private final String recrueRoleId;
    private final MongoCollection<Document> userCollection;

    public GuildMemberJoinListeners() {
        Dotenv dotenv = Dotenv.load();
        this.recrueRoleId = dotenv.get("RECRUE_ROLE_ID");
        this.userCollection = Main.getMongoConnection().getDatabase().getCollection("users");
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

        Main.getDraftMessageHandler().createMessage(e.getGuild(), e.getMember());
        Document contract = Main.getContractManager().generateEntryContract(member);
        if (contract != null) {
            String teamName = contract.getString("teamName");
            double salary = contract.getInteger("salary") / 1000000.0;
            e.getUser().openPrivateChannel().queue(channel -> {
                channel.sendMessage("🎉 **Bienvenue sur le serveur Dans le Mix !**\n\n" +
                        "Vous avez signé un contrat d'entrée avec les **" + teamName + "** " +
                        "pour 3 ans à un salaire annuel de $" + String.format("%.3fM", salary) + ".\n\n" +
                        "Ce contrat est à deux volets, ce qui signifie que vous pourriez être envoyé dans les ligues mineures.\n\n" +
                        "Utilisez la commande `/rank` dans le serveur pour voir votre profil et `/contrat voir` " +
                        "pour les détails de votre contrat.\n\n" +
                        "Bonne chance pour votre carrière ! 🏒").queue();
            });
        }
    }
}
