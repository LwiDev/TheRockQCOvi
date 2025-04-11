package ca.lwi.trqcbot.commands.manager;

import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.commands.list.ComRank;
import ca.lwi.trqcbot.commands.list.ComTR8;
import ca.lwi.trqcbot.commands.list.ComTicket;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CommandsManager extends ListenerAdapter {

    private final List<Command> commands = new ArrayList<>();

    public CommandsManager() {
        registerCommands();
    }

    public void registerCommands(){
        registerCommand(new ComRank());
        registerCommand(new ComTicket());
        registerCommand(new ComTR8());
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
    }
}
