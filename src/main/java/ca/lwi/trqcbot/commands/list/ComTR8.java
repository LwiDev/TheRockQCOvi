package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.handlers.RulesMessageHandler;
import ca.lwi.trqcbot.handlers.VeteranMessageHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class ComTR8 extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComTR8.class);

    public ComTR8() {
        super("tr8", "Commande principale");

        setDefaultPermissions(DefaultMemberPermissions.DISABLED);
        SubcommandData welcomeCmd = new SubcommandData("welcome", "Créer un message de bienvenue basé sur les données existantes")
                .addOption(OptionType.USER, "utilisateur", "L'utilisateur pour lequel créer le message (par défaut: vous-même)", false);

        // Sous-commandes de Resources
        SubcommandGroupData resourcesGroup = new SubcommandGroupData("resources", "Gérer le message du salon ressources");
        SubcommandData resourcesUpdateCmd = new SubcommandData("update", "Mettre à jour le message des ressources");
        resourcesUpdateCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        SubcommandData manageCmd = new SubcommandData("manage", "Gérer les sections et les éléments du message des ressources");
        SubcommandData viewConfigCmd = new SubcommandData("view", "Afficher la configuration actuelle des ressources");
        resourcesGroup.addSubcommands(resourcesUpdateCmd, manageCmd, viewConfigCmd);
        
        // Sous-commandes de veteran
        SubcommandGroupData messagesGroup = new SubcommandGroupData("messages", "Gérer le message du salon règlement");
        SubcommandData rulesCmd = new SubcommandData("rules", "Mettre à jour le message du salon règlement");
        rulesCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        SubcommandData veteranCmd = new SubcommandData("veteran", "Mettre à jour le message du salon devenir-vétéran");
        veteranCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        messagesGroup.addSubcommands(rulesCmd, veteranCmd);
        
        // Ajouter les sous-commandes et le groupe à la commande principale
        addSubcommands(welcomeCmd);
        addSubcommandGroups(resourcesGroup, messagesGroup);
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (!e.isFromGuild()) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        String subcommandName = e.getSubcommandName();
        String subcommandGroup = e.getSubcommandGroup();

        if (subcommandName == null) {
            e.reply("Veuillez spécifier une sous-commande.").setEphemeral(true).queue();
            return;
        }
        
        if (subcommandGroup == null) {
            e.reply("Veuillez spécifier une sous-commande.").setEphemeral(true).queue();
            return;
        }

        try {
            if (subcommandGroup.equals("resources")) {
                switch (subcommandName.toLowerCase()) {
                    case "update":
                        e.deferReply(true).queue();
                        Main.getResourcesManager().handleUpdate(e);
                        break;
                    case "manage":
                        Main.getResourcesManager().handleManage(e);
                        break;
                    case "view":
                        e.deferReply(true).queue();
                        Main.getResourcesManager().handleViewConfig(e);
                        break;
                    default:
                        e.reply("Sous-commande inconnue dans le groupe resources.").setEphemeral(true).queue();
                }
            } else if (subcommandGroup.equals("messages")) {
                switch (subcommandName.toLowerCase()) {
                    case "rules":
                        e.deferReply(true).queue();
                        handleUpdateRules(e);
                        break;
                    case "veteran":
                        e.deferReply(true).queue();
                        handleUpdateVeteran(e);
                        break;
                    default:
                        e.reply("Sous-commande inconnue dans le groupe vétéran.").setEphemeral(true).queue();
                }
            } else {
                switch (subcommandName.toLowerCase()) {
                    case "welcome":
                        e.deferReply(true).queue();
                        handleWelcomeMessage(e);
                        break;
                    default:
                        e.reply("Sous-commande inconnue.").setEphemeral(true).queue();
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}{}: {}", subcommandGroup + " ", subcommandName, ex.getMessage());
            if (e.isAcknowledged()) {
                e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
            } else {
                e.reply("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
            }
        }
    }

    private void handleWelcomeMessage(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        if (guild == null) {
            e.getHook().sendMessage("Impossible de trouver le serveur.").setEphemeral(true).queue();
            return;
        }
        Member targetMember = e.getOption("utilisateur") != null ? Objects.requireNonNull(e.getOption("utilisateur")).getAsMember() : e.getMember();
        if (targetMember == null) {
            e.getHook().sendMessage("Impossible de trouver cet utilisateur.").setEphemeral(true).queue();
            return;
        }
        Main.getWelcomeMessageHandler().createWelcomeMessageFromDb(guild, targetMember);
        e.getHook().deleteOriginal().queue();
    }

    public void handleUpdateRules(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();
        RulesMessageHandler.updateRulesMessage(forceNew)
                .thenAccept(v -> e.getHook().sendMessage("✅ Le message a été mis à jour.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                }))
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour du message : {}", error.getMessage(), error);
                    e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue();
                    return null;
                });
    }

    public void handleUpdateVeteran(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();
        VeteranMessageHandler.updateVeteranMessage(forceNew)
                .thenAccept(v -> e.getHook().sendMessage("✅ Le message a été mis à jour.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                }))
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour du message : {}", error.getMessage(), error);
                    e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue();
                    return null;
                });
    }
}