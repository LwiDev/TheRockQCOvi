package src.ca.lwi.trqcbot.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.internal.interactions.CommandDataImpl;
import org.jetbrains.annotations.NotNull;

public abstract class Command extends CommandDataImpl {

    public Command(@NotNull String name, @NotNull String description) {
        super(name, description);
    }

    public void onSlash(SlashCommandInteractionEvent e){}

    public boolean isGuildCommand(){
        return true;
    }
}
