package src.ca.lwi.trqcbot.commands.list;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import src.ca.lwi.trqcbot.Main;
import src.ca.lwi.trqcbot.commands.Command;

import java.util.Objects;

public class ComLink extends Command {

    public ComLink() {
        super("link", "Liez votre compte YouTube à votre compte Discord");
        addOption(OptionType.STRING, "username", "Votre nom d'utilisateur YouTube", true);
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        String youtubeUsername = Objects.requireNonNull(e.getOption("username")).getAsString();
        String userId = e.getUser().getId();
        Main.getRankManager().linkYoutubeAccount(userId, youtubeUsername);
        e.reply("Votre compte YouTube a été lié avec succès. Utilisez `/verify` pour vérifier votre statut d'abonnement.").setEphemeral(true).queue();
    }
}