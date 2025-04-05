package src.ca.lwi.trqcbot;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import src.ca.lwi.trqcbot.commands.manager.CommandsManager;
import src.ca.lwi.trqcbot.handlers.WelcomeMessageHandler;
import src.ca.lwi.trqcbot.listeners.JoinListener;
import src.ca.lwi.trqcbot.mongo.MongoConnection;
import src.ca.lwi.trqcbot.mongo.MongoCredentials;
import src.ca.lwi.trqcbot.ranks.RankManager;

public class Main {

    @Getter
    private static JDA jda;
    @Getter
    private static MongoConnection mongoConnection;
    @Getter
    private static RankManager rankManager;
    @Getter
    private static WelcomeMessageHandler welcomeMessageHandler;

    public static void main(String[] args) {
        System.setProperty("log4j2.disable.jmx", "true");
        System.out.println("Starting server...");

        Dotenv dotenv = Dotenv.load();

        MongoCredentials mongoCredentials = new MongoCredentials(dotenv.get("DB_IP"), dotenv.get("DB_PASSWORD"), dotenv.get("DB_USERNAME"), dotenv.get("DB_DATABASE"));
        mongoConnection = new MongoConnection(mongoCredentials);
        try {
            mongoConnection.init();
        } catch (Exception e) {
            System.err.println("Failed to initialize MongoDB: " + e.getMessage());
        }

        rankManager = new RankManager();

//        new Log().init(new BotCommandManager());
        jda = JDABuilder
                .create(dotenv.get("DISC_TOKEN"), GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setActivity(Activity.playing("Match de Hockey"))
                .addEventListeners(rankManager)
                .addEventListeners(new JoinListener())
                .addEventListeners(new CommandsManager())
                .build();

        welcomeMessageHandler = new WelcomeMessageHandler();
    }

    public static boolean isTR8Guild(Guild guild) {
        Dotenv dotenv = Dotenv.load();
        return guild != null && guild.getId().equals(dotenv.get("GUILD_ID"));
    }

    public static String getDiscordName() {
        return "Dans le Mix | TheRockQC";
    }
}
