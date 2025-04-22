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
                .append("contractStatus", "signed")
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
    }
}
