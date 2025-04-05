package src.ca.lwi.trqcbot.commands.manager;

import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import src.ca.lwi.trqcbot.commands.Command;
import src.ca.lwi.trqcbot.commands.list.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CommandsManager extends ListenerAdapter {

    private final List<Command> commands = new ArrayList<>();
    private static final long ADMIN_ID = 1356761277661315204L;

    public CommandsManager() {
        registerCommands();
    }

    public void registerCommands(){
        registerCommand(new ComLink());
        registerCommand(new ComRank());
        registerCommand(new ComResources());
        registerCommand(new ComTR8());
        registerCommand(new ComVerify());
    }

    private void registerCommand(Command command) {
        this.commands.add(command);
    }

    public Command getCommand(String name) {
        return commands.stream()
                .filter(cmd -> cmd.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent e) {
        if (Objects.requireNonNull(e.getMember()).getRoles().stream().noneMatch(role -> role.getIdLong() == ADMIN_ID)) {
            e.reply("Vous n'avez pas la permission d'utiliser cette commande.").setEphemeral(true).queue();
            return;
        }
        this.commands.stream().filter(command -> command.getName().equalsIgnoreCase(e.getName())).findFirst().ifPresent(command -> command.onSlash(e));
    }

    @Override
    public void onGuildReady(@NotNull GuildReadyEvent e) {
        e.getGuild().updateCommands().addCommands(this.commands.stream().filter(Command::isGuildCommand).collect(Collectors.toList())).queue();
    }

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent e) {
        e.getGuild().updateCommands().addCommands(this.commands.stream().filter(Command::isGuildCommand).collect(Collectors.toList())).queue();
    }

    @Override
    public void onReady(@NotNull ReadyEvent e) {
        e.getJDA().updateCommands().addCommands(this.commands.stream().filter(command -> !command.isGuildCommand()).collect(Collectors.toList())).queue();
        ComResources resourcesCmd = (ComResources) getCommand("resources");
        if (resourcesCmd != null) {
            resourcesCmd.registerEventListeners(e.getJDA());
        }
    }
}
