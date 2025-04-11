package ca.lwi.trqcbot.commands.list;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ComTR8 extends Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComTR8.class);

    private final String recrueRoleId;
    private final String joueurRoleId;
    private final String veteranRoleId;

    public ComTR8() {
        super("tr8", "Commande principale");

        Dotenv dotenv = Dotenv.load();
        this.recrueRoleId = dotenv.get("RECRUE_ROLE_ID");
        this.joueurRoleId = dotenv.get("JOUEUR_ROLE_ID");
        this.veteranRoleId = dotenv.get("VETERAN_ROLE_ID");

        setDefaultPermissions(DefaultMemberPermissions.DISABLED);
        SubcommandData welcomeCmd = new SubcommandData("welcome", "Créer un faux message de bienvenue");
        SubcommandData roleCmd = new SubcommandData("role", "Attribuer un rôle spécifique à un utilisateur");
        roleCmd.addOptions(
                new OptionData(OptionType.USER, "utilisateur", "L'utilisateur à qui attribuer le rôle", true),
                new OptionData(OptionType.STRING, "role", "Le rôle à attribuer (recrue, joueur, veteran)", true)
                        .addChoice("Recrue", "recrue")
                        .addChoice("Joueur", "joueur")
                        .addChoice("Vétéran", "veteran")
        );
        SubcommandData registerMembersCmd = new SubcommandData("registermembers", "Enregistrer les membres existants dans la base de données avec leur rôle actuel");

        addSubcommands(welcomeCmd, roleCmd, registerMembersCmd);
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
                case "role":
                    handleRole(e);
                case "registermembers":
                    handleRegisterMembers(e);
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

    private void handleRole(SlashCommandInteractionEvent e) {
        // Récupérer les paramètres
        Member targetMember = e.getOption("utilisateur").getAsMember();
        String roleType = e.getOption("role").getAsString();

        if (targetMember == null) {
            e.getHook().sendMessage("Impossible de trouver cet utilisateur sur le serveur.").queue();
            return;
        }

        Guild guild = e.getGuild();
        String userId = targetMember.getId();

        try {
            // Récupérer les objets de rôle
            Role recrueRole = guild.getRoleById(recrueRoleId);
            Role joueurRole = guild.getRoleById(joueurRoleId);
            Role veteranRole = guild.getRoleById(veteranRoleId);

            if (recrueRole == null || joueurRole == null || veteranRole == null) {
                e.getHook().sendMessage("Erreur: Un ou plusieurs rôles n'ont pas été trouvés. Vérifiez vos variables d'environnement.").queue();
                return;
            }

            // Supprimer tous les rôles existants liés au système
            if (targetMember.getRoles().contains(recrueRole)) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), recrueRole).queue();
            }
            if (targetMember.getRoles().contains(joueurRole)) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), joueurRole).queue();
            }
            if (targetMember.getRoles().contains(veteranRole)) {
                guild.removeRoleFromMember(UserSnowflake.fromId(userId), veteranRole).queue();
            }

            // Attribuer le nouveau rôle et mettre à jour la base de données
            String rankName;
            Role roleToAdd;

            switch (roleType) {
                case "recrue":
                    rankName = "Recrue";
                    roleToAdd = recrueRole;
                    break;
                case "joueur":
                    rankName = "Joueur";
                    roleToAdd = joueurRole;
                    break;
                case "veteran":
                    rankName = "Vétéran";
                    roleToAdd = veteranRole;
                    break;
                default:
                    e.getHook().sendMessage("Type de rôle non reconnu.").queue();
                    return;
            }

            // Ajouter le rôle
            guild.addRoleToMember(UserSnowflake.fromId(userId), roleToAdd).queue();

            // Mettre à jour la base de données via le RankManager
            Main.getRankManager().updateUserRank(userId, rankName);
            e.getHook().sendMessage("✅ Le rôle **" + rankName + "** a été attribué à " + targetMember.getAsMention() + " et la base de données a été mise à jour.").queue();
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", "role", ex.getMessage());
            e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleRegisterMembers(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();

        try {
            // Récupérer les objets de rôle
            Role recrueRole = guild.getRoleById(recrueRoleId);
            Role joueurRole = guild.getRoleById(joueurRoleId);
            Role veteranRole = guild.getRoleById(veteranRoleId);

            if (recrueRole == null || joueurRole == null || veteranRole == null) {
                e.getHook().sendMessage("Erreur: Un ou plusieurs rôles n'ont pas été trouvés. Vérifiez vos variables d'environnement.").queue();
                return;
            }

            // Récupérer tous les membres du serveur
            guild.loadMembers().onSuccess(members -> {
                AtomicInteger veteranCount = new AtomicInteger(0);
                AtomicInteger joueurCount = new AtomicInteger(0);
                AtomicInteger recrueCount = new AtomicInteger(0);
                AtomicInteger totalRegistered = new AtomicInteger(0);

                // Traiter chaque membre
                for (Member member : members) {
                    if (member.getUser().isBot()) continue;
                    String userId = member.getId();
                    List<Role> memberRoles = member.getRoles();
                    String rank;
                    if (memberRoles.contains(veteranRole)) {
                        rank = "Vétéran";
                        veteranCount.incrementAndGet();
                    } else if (memberRoles.contains(joueurRole)) {
                        rank = "Joueur";
                        joueurCount.incrementAndGet();
                    } else if (memberRoles.contains(recrueRole)) {
                        recrueCount.incrementAndGet();
                        continue;
                    } else {
                        rank = "Recrue";
                    }

                    // Enregistrer le membre avec son rang dans la base de données
                    Main.getRankManager().registerExistingUser(userId, member.getUser().getName(), rank);
                    totalRegistered.incrementAndGet();
                }

                // Envoyer le rapport
                e.getHook().sendMessage("✅ Enregistrement des membres terminé!\n\n" +
                        "**Membres enregistrés:** " + totalRegistered.get() + "\n" +
                        "**Répartition:**\n" +
                        "- Vétérans: " + veteranCount.get() + "\n" +
                        "- Joueurs: " + joueurCount.get() + "\n" +
                        "- Recrues ignorées: " + recrueCount.get()).queue();
            }).onError(error -> {
                e.getHook().sendMessage("❌ Une erreur s'est produite lors du chargement des membres: " + error.getMessage()).queue();
                error.printStackTrace();
            });
        } catch (Exception ex) {
            LOGGER.error("❌ Erreur lors de l'exécution de la commande {}: {}", "registermembers", ex.getMessage());
            e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
        }
    }
}