package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.donations.DonationsHandler;
import ca.lwi.trqcbot.handlers.RulesMessageHandler;
import ca.lwi.trqcbot.handlers.VeteranMessageHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
        
        // Sous-commandes de messages
        SubcommandGroupData messagesGroup = new SubcommandGroupData("messages", "Gérer le message du salon règlement");
        SubcommandData rulesCmd = new SubcommandData("rules", "Mettre à jour le message du salon règlement");
        rulesCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        SubcommandData veteranCmd = new SubcommandData("veteran", "Mettre à jour le message du salon devenir-vétéran");
        veteranCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        messagesGroup.addSubcommands(rulesCmd, veteranCmd);

        // Sous-commandes de Donations
        SubcommandGroupData donationsGroup = new SubcommandGroupData("dons", "Gérer le message du salon règlement");
        SubcommandData showDonorCmd = new SubcommandData("update", "Afficher le tableau des donateurs");
        showDonorCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);
        SubcommandData addDonorCmd = new SubcommandData("add", "Ajouter un don");
        addDonorCmd.addOption(OptionType.STRING, "donateur", "Nom du donateur (laissez vide pour un nouveau donateur)", false, true);
        addDonorCmd.addOption(OptionType.INTEGER, "montant", "Montant du don (optionnel, sinon une fenêtre s'ouvrira)", false);
        SubcommandData viewCmd = new SubcommandData("view", "Voir la liste des donateurs de façon interactive");
        SubcommandData removeDonationCmd = new SubcommandData("remove", "Retirer un don à un donateur existant");
        removeDonationCmd.addOption(OptionType.STRING, "donateur", "Nom du donateur", true, true);
        removeDonationCmd.addOption(OptionType.INTEGER, "montant", "Montant du don (optionnel, sinon une fenêtre s'ouvrira)", false);
        donationsGroup.addSubcommands(showDonorCmd, addDonorCmd, viewCmd, removeDonationCmd);

        // Ajouter les sous-commandes et le groupe à la commande principale
        addSubcommands(welcomeCmd);
        addSubcommandGroups(resourcesGroup, messagesGroup, donationsGroup);
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

        try {
            if (subcommandGroup == null) {
                if (subcommandName.equalsIgnoreCase("welcome")) {
                    e.deferReply(true).queue();
                    handleWelcomeMessage(e);
                } else {
                    e.reply("Sous-commande inconnue.").setEphemeral(true).queue();
                }
            } else {
                switch (subcommandGroup) {
                    case "dons" -> {
                        switch (subcommandName.toLowerCase()) {
                            case "update":
                                e.deferReply(true).queue();
                                Main.getDonationsManager().handleUpdateDonors(e);
                                break;
                            case "add":
                                Main.getDonationsManager().handleAddDonation(e);
                                break;
                            case "remove":
                                Main.getDonationsManager().handleRemoveDonation(e);
                                break;
                            case "view":
                                handleViewDonors(e);
                                break;
                            default:
                                e.reply("Sous-commande inconnue dans le groupe donations.").setEphemeral(true).queue();
                        }
                    }
                    case "resources" -> {
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
                    }
                    case "messages" -> {
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
                    }
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

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) {
        if (Objects.equals(e.getSubcommandGroup(), "dons") && (Objects.equals(e.getSubcommandName(), "add") || Objects.equals(e.getSubcommandName(), "remove")) && e.getFocusedOption().getName().equals("donateur")) {
            String value = e.getFocusedOption().getValue().toLowerCase();
            List<String> donorNames = DonationsHandler.getDonorNamesList();
            List<String> filteredOptions = donorNames.stream()
                    .filter(name -> name.toLowerCase().contains(value))
                    .sorted(String::compareToIgnoreCase)
                    .limit(25)
                    .collect(Collectors.toList());
            e.replyChoiceStrings(filteredOptions).queue();
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

    private void handleUpdateRules(SlashCommandInteractionEvent e) {
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

    private void handleUpdateVeteran(SlashCommandInteractionEvent e) {
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

    private void handleViewDonors(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        var embed = DonationsHandler.getPageEmbed(userId, 0);
        var buttons = DonationsHandler.createPaginationButtons(userId);
        event.replyEmbeds(embed).addActionRow(buttons).setEphemeral(true).queue();
    }
}