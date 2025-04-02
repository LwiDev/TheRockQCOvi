package src.ca.lwi.trqcbot.commands.list;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import src.ca.lwi.trqcbot.Main;
import src.ca.lwi.trqcbot.commands.Command;

public class ComTR8 extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComTR8.class);

    public ComTR8() {
        super("tr8", "Commande principale");

        setDefaultPermissions(DefaultMemberPermissions.DISABLED);
        SubcommandData welcomeCmd = new SubcommandData("welcome", "Créer un faux message de bienvenue");
        addSubcommands(welcomeCmd);
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (!e.isFromGuild()) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        e.deferReply(true).queue();

        String subcommandName = e.getSubcommandName();
        if (subcommandName == null) {
            e.getHook().sendMessage("Veuillez spécifier une sous-commande.").setEphemeral(true).queue();
            return;
        }

        try {
            switch (subcommandName.toLowerCase()) {
                case "welcome":
                    handleWelcomeMessage(e);
                    break;
                default:
                    e.getHook().sendMessage("Sous-commande inconnue.").setEphemeral(true).queue();
            }
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", subcommandName, ex.getMessage());
            e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleWelcomeMessage(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        if (guild == null) {
            e.getHook().sendMessage("Impossible de trouver le serveur.").setEphemeral(true).queue();
            return;
        }
        Main.getWelcomeMessageHandler().createMessage(guild, e.getMember());
        e.getHook().deleteOriginal().queue();
    }
}