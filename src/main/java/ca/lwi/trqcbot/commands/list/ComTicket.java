package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComTicket extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComTicket.class);
    private static final String MODERATOR_ROLE_ID = "1356761423757185064";

    public ComTicket() {
        super("ticket", "Gestion des tickets");
        setDefaultPermissions(DefaultMemberPermissions.DISABLED);
        SubcommandData archiveCmd = new SubcommandData("archive", "Archiver un ticket");
        SubcommandData unarchiveCmd = new SubcommandData("unarchive", "Désarchiver un ticket archivé");
        SubcommandData setupCmd = new SubcommandData("setup", "Initialiser le système de tickets");
        addSubcommands(archiveCmd, unarchiveCmd, setupCmd);
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

        // Vérification des permissions selon la sous-commande
        boolean hasPermission = false;
        Member member = e.getMember();
        Guild guild = e.getGuild();

        if (member == null) {
            e.getHook().sendMessage("Impossible de vérifier vos permissions.").setEphemeral(true).queue();
            return;
        }

        switch (subcommandName.toLowerCase()) {
            case "archive":
            case "unarchive":
                // Modérateurs + Admin
                hasPermission = member.hasPermission(Permission.ADMINISTRATOR) || hasModeratorRole(member, guild);
                break;
            case "setup":
                // Admin uniquement
                hasPermission = member.hasPermission(Permission.ADMINISTRATOR);
                break;
            default:
                e.getHook().sendMessage("Sous-commande inconnue.").setEphemeral(true).queue();
                return;
        }

        if (!hasPermission) {
            e.getHook().sendMessage("Vous n'avez pas la permission d'utiliser cette commande.").setEphemeral(true).queue();
            return;
        }

        try {
            switch (subcommandName.toLowerCase()) {
                case "archive":
                    handleArchive(e);
                    break;
                case "unarchive":
                    handleUnarchive(e);
                    break;
                case "setup":
                    handleSetup(e);
                    break;
                default:
                    e.getHook().sendMessage("Sous-commande inconnue.").setEphemeral(true).queue();
            }
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", subcommandName, ex.getMessage());
            e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleArchive(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        if (guild == null) {
            e.getHook().sendMessage("Impossible de trouver le serveur.").setEphemeral(true).queue();
            return;
        }
        Main.getTicketsHandler().handleArchive(e.getChannel());
        e.getHook().sendMessage("Le ticket a été archivé.").queue();
    }

    private void handleUnarchive(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        if (guild == null) {
            e.getHook().sendMessage("Impossible de trouver le serveur.").setEphemeral(true).queue();
            return;
        }
        Main.getTicketsHandler().handleUnarchiveCommand(e);
    }

    private void handleSetup(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        if (guild == null) {
            e.getHook().sendMessage("Impossible de trouver le serveur.").setEphemeral(true).queue();
            return;
        }
        Main.getTicketsHandler().initialize(guild);
        e.getHook().sendMessage("Le système de tickets a été initialisé avec succès.").setEphemeral(true).queue();
    }

    private boolean hasModeratorRole(Member member, Guild guild) {
        Role modRole = guild.getRoleById(MODERATOR_ROLE_ID);
        return modRole != null && member.getRoles().contains(modRole);
    }
}