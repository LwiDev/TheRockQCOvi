package ca.lwi.trqcbot.listeners;

import ca.lwi.trqcbot.Main;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class ReadyListeners extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadyListeners.class);
    private final String guildId;

    public ReadyListeners() {
        this.guildId = Dotenv.load().get("GUILD_ID");
    }

    public void onReady(ReadyEvent e) {
        LOGGER.info("Bot en ligne");
        Guild guild = e.getJDA().getGuildById(guildId);
        if (guild == null) {
            LOGGER.error("Guild non trouvée avec l'ID: {}", guildId);
            return;
        }
        e.getJDA().updateCommands().addCommands(Main.getCommandsManager().getCommands().stream().filter(command -> !command.isGuildCommand()).collect(Collectors.toList())).queue();
        Main.getMembersRecoveryHandler().init(guild);
        Main.getContractsRecoveryHandler().init(guild);
        Main.getContractsManager().checkExpiringContracts();
    }

}
