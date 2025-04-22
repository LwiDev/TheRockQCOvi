package ca.lwi.trqcbot;

import ca.lwi.trqcbot.commands.manager.CommandsManager;
import ca.lwi.trqcbot.contracts.ContractManager;
import ca.lwi.trqcbot.donations.DonationsManager;
import ca.lwi.trqcbot.draft.DraftMessageHandler;
import ca.lwi.trqcbot.listeners.GuildMemberJoinListeners;
import ca.lwi.trqcbot.mongo.MongoConnection;
import ca.lwi.trqcbot.mongo.MongoCredentials;
import ca.lwi.trqcbot.ranks.RankManager;
import ca.lwi.trqcbot.recovery.ContractRecoveryHandler;
import ca.lwi.trqcbot.recovery.MemberRecoveryHandler;
import ca.lwi.trqcbot.ressources.ResourcesManager;
import ca.lwi.trqcbot.teams.TeamManager;
import ca.lwi.trqcbot.tickets.TicketsHandler;
import ca.lwi.trqcbot.utils.FontUtils;
import ca.lwi.trqcbot.youtube.YouTubeWatcher;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.util.Scanner;

public class Main {

    @Getter
    private static JDA jda;
    @Getter
    private static MongoConnection mongoConnection;
    @Getter
    private static DonationsManager donationsManager;
    @Getter
    private static RankManager rankManager;
    @Getter
    private static ResourcesManager resourcesManager;
    @Getter
    private static TeamManager teamManager;
    @Getter
    private static TicketsHandler ticketsHandler;
    @Getter
    private static DraftMessageHandler draftMessageHandler;
    @Getter
    private static ContractManager contractManager;
    @Getter
    private static MemberRecoveryHandler membersRecoveryHandler;
    @Getter
    private static ContractRecoveryHandler contractsRecoveryHandler;

    public static void main(String[] args) throws IOException, FontFormatException {
        System.setProperty("log4j2.disable.jmx", "true");
        System.out.println("Starting server...");

        Dotenv dotenv = Dotenv.load();
        FontUtils.loadFonts();

        MongoCredentials mongoCredentials = new MongoCredentials(dotenv.get("DB_IP"), dotenv.get("DB_PASSWORD"), dotenv.get("DB_USERNAME"), dotenv.get("DB_DATABASE"));
        mongoConnection = new MongoConnection(mongoCredentials);
        try {
            mongoConnection.init();
        } catch (Exception e) {
            System.err.println("Failed to initialize MongoDB: " + e.getMessage());
        }

        donationsManager = new DonationsManager();
        rankManager = new RankManager();
        resourcesManager = new ResourcesManager();
        teamManager = new TeamManager();
        ticketsHandler = new TicketsHandler();
        draftMessageHandler = new DraftMessageHandler();
        YouTubeWatcher watcher = new YouTubeWatcher();
        contractManager = new ContractManager();
        membersRecoveryHandler = new MemberRecoveryHandler();
        contractsRecoveryHandler = new ContractRecoveryHandler();

        jda = JDABuilder
                .create(dotenv.get("DISC_TOKEN"), GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_VOICE_STATES)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setActivity(Activity.playing("Match de Hockey"))
                .addEventListeners(donationsManager)
                .addEventListeners(rankManager)
                .addEventListeners(ticketsHandler)
                .addEventListeners(contractManager)
                .addEventListeners(membersRecoveryHandler)
                .addEventListeners(contractsRecoveryHandler)
                .addEventListeners(new GuildMemberJoinListeners())
                .addEventListeners(new CommandsManager())
                .build();

        resourcesManager.registerEventListeners(jda);
        watcher.start(jda);

        Thread consoleThread = getThread();
        consoleThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Arrêt du bot en cours...");
            if (rankManager != null) rankManager.shutdown();
            if (mongoConnection != null) mongoConnection.close();
            if (membersRecoveryHandler != null) membersRecoveryHandler.shutdown();
            if (contractsRecoveryHandler != null) contractsRecoveryHandler.shutdown();
            if (jda != null) jda.shutdown();
            System.out.println("Arrêt terminé.");
        }));
    }

    @NotNull
    private static Thread getThread() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            String line;
            while (scanner.hasNextLine()) {
                line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("stop") || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("shutdown")) {
                    System.out.println("Commande d'arrêt reçue. Fermeture du bot...");
                    scanner.close();
                    System.exit(0);
                    return;
                } else if (line.equalsIgnoreCase("help")) {
                    System.out.println("Commandes disponibles : stop, exit, shutdown, help");
                }
            }
        });
        consoleThread.setDaemon(true);
        return consoleThread;
    }

    public static boolean isTR8Guild(Guild guild) {
        return guild != null && guild.getId().equals(Dotenv.load().get("GUILD_ID"));
    }
}
