package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.contracts.ContractManager;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.bson.Document;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ComContract extends Command {

    private final ContractManager contractManager;

    public ComContract() {
        super("contrat", "Gérer vos contrats et voir les offres disponibles");
        setDefaultPermissions(DefaultMemberPermissions.ENABLED);
        
        SubcommandData viewContract = new SubcommandData("view", "Afficher les détails de votre contrat actuel");
        SubcommandData requestOffers = new SubcommandData("request", "Recevoir de nouvelles offres de contrat (agents libres seulement)");
        SubcommandData adminGenerate = new SubcommandData("generate", "Générer des offres de contrat pour un utilisateur (Admin seulement)")
                .addOption(OptionType.USER, "utilisateur", "L'utilisateur pour qui générer des offres", true);
        
        // Ajouter les sous-commandes
        addSubcommands(viewContract, requestOffers, adminGenerate);
        
        this.contractManager = Main.getContractManager();
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) {
            e.reply("Cette commande doit être utilisée sur un serveur.").setEphemeral(true).queue();
            return;
        }
        
        String subcommand = e.getSubcommandName();
        if (subcommand == null) {
            e.reply("Une erreur est survenue.").setEphemeral(true).queue();
            return;
        }
        
        switch (subcommand) {
            case "view":
                showCurrentContract(e);
                break;
            case "request":
                requestNewOffers(e);
                break;
            case "generate":
                if (!hasAdminPermission(e.getMember())) {
                    e.reply("Vous n'avez pas la permission d'utiliser cette commande.").setEphemeral(true).queue();
                    return;
                }
                OptionMapping userOption = e.getOption("utilisateur");
                if (userOption == null) {
                    e.reply("Utilisateur non spécifié.").setEphemeral(true).queue();
                    return;
                }
                generateOffersForUser(e, userOption.getAsUser());
                break;
            default:
                e.reply("Sous-commande inconnue.").setEphemeral(true).queue();
        }
    }
    
    /**
     * Affiche les détails du contrat actuel de l'utilisateur.
     * @param e L'événement de commande
     */
    private void showCurrentContract(SlashCommandInteractionEvent e) {
        e.deferReply().queue();

        User user = e.getUser();
        Document userData = Main.getRankManager().getUserData(user.getId());

        if (userData == null) {
            e.getHook().sendMessage("Vous n'avez pas encore de profil sur le serveur.").queue();
            return;
        }

        String teamName = userData.getString("teamName");

        // Vérifier si l'utilisateur a un contrat dans le document user
        Document contractData = (Document) userData.get("contract");

        if (contractData == null || teamName.equals("Agent Libre")) {
            e.getHook().sendMessage("Vous êtes actuellement un **Agent Libre**. Utilisez `/contrat request` pour recevoir de nouvelles offres.").queue();
            return;
        }

        // Formater les détails du contrat directement depuis le sous-document contract
        String teamNameFromContract = contractData.getString("teamName");
        int years = contractData.getInteger("years");
        double salary = contractData.get("salary", Number.class).doubleValue() / 1000000.0; // Convertir en millions
        String contractType = contractData.getString("type");
        Date startDate = contractData.getDate("startDate");
        Date expiryDate = contractData.getDate("expiryDate");

        // Définir explicitement la locale française
        Locale frLocale = Locale.CANADA_FRENCH;
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMMM yyyy", frLocale);

        StringBuilder response = new StringBuilder();
        response.append("📋 **Votre contrat actuel :**\n\n");
        response.append("🏒 **Équipe :** ").append(teamNameFromContract).append("\n");
        response.append("⏳ **Durée :** ").append(years).append(" an").append(years > 1 ? "s" : "").append("\n");
        response.append("💰 **Salaire annuel :** $").append(String.format("%.2fM", salary)).append("\n");
        response.append("📝 **Type de contrat :** ").append(contractType).append("\n");
        response.append("🗓️ **Signé le :** ").append(dateFormat.format(startDate)).append("\n");
        response.append("⏰ **Expire le :** ").append(dateFormat.format(expiryDate)).append("\n\n");

        long timeRemaining = expiryDate.getTime() - System.currentTimeMillis();
        long daysRemaining = timeRemaining / (1000 * 60 * 60 * 24);

        response.append("⚠️ **Temps restant :** ");
        if (daysRemaining <= 0) {
            response.append("Votre contrat a expiré ou est sur le point d'expirer. Vous recevrez bientôt de nouvelles offres.");
        } else if (daysRemaining == 1) {
            response.append("1 jour");
        } else {
            response.append(daysRemaining).append(" jours");
        }

        e.getHook().sendMessage(response.toString()).queue();
    }
    
    /**
     * Permet à un agent libre de demander de nouvelles offres de contrat.
     * @param e L'événement de commande
     */
    private void requestNewOffers(SlashCommandInteractionEvent e) {
        e.deferReply().queue();
        
        Member member = e.getMember();
        if (member == null) return;
        Document userData = Main.getRankManager().getUserData(member.getId());
        
        if (userData == null) {
            // Nouvel utilisateur, créer un contrat d'entrée
            Document entryContract = contractManager.generateEntryContract(e.getMember());
            if (entryContract != null) {
                String teamName = entryContract.getString("teamName");
                e.getHook().sendMessage("Bienvenue dans la ligue ! Vous avez signé un contrat d'entrée de 3 ans avec **" +
                                            teamName + "**. Utilisez `/rank` pour voir votre profil.").queue();
            } else {
                e.getHook().sendMessage("Une erreur est survenue lors de la génération de votre contrat d'entrée.").queue();
            }
            return;
        }

        String teamName = userData.getString("teamName");
        if (!teamName.equals("Agent Libre")) {
            Document activeContract = userData.get("contract", Document.class);
            if (activeContract != null && activeContract.getString("status").equals("active")) {
                Date expiryDate = activeContract.getDate("expiryDate");
                if (expiryDate.after(new Date())) {
                    // Le contrat n'est pas encore expiré
                    long timeRemaining = expiryDate.getTime() - System.currentTimeMillis();
                    long daysRemaining = timeRemaining / (1000 * 60 * 60 * 24);

                    e.getHook().sendMessage("Vous avez déjà un contrat en cours avec **" + teamName +
                            "**. Il expire dans " + daysRemaining + " jour(s).").queue();
                    return;
                }
            }
        }
        
        // Vérifier si des offres sont déjà en attente
        Document pendingOffers = Main.getMongoConnection().getDatabase().getCollection("contractOffers")
                .find(new Document("userId", member.getId()).append("status", "pending"))
                .first();
        
        if (pendingOffers != null) {
            e.getHook().sendMessage("Vous avez déjà des offres de contrat en attente. " +
                                        "Vérifiez vos messages privés ou contactez un administrateur si vous ne les trouvez pas.").queue();
            return;
        }
        
        // Générer de nouvelles offres
        contractManager.sendContractOffers(e.getUser());
        
        e.getHook().sendMessage("Des offres de contrat vous ont été envoyées en message privé. " +
                                    "Vous avez 3 jours pour en accepter une, sinon vous deviendrez un agent libre.").queue();
    }
    
    /**
     * Permet à un administrateur de générer des offres pour un utilisateur.
     * @param e L'événement de commande
     * @param targetUser L'utilisateur cible
     */
    private void generateOffersForUser(SlashCommandInteractionEvent e, User targetUser) {
        e.deferReply().queue();
        
        try {
            // Vérifier si l'utilisateur est sur le serveur
            Member targetMember = Objects.requireNonNull(e.getGuild()).retrieveMember(targetUser).complete();
            if (targetMember == null) {
                e.getHook().sendMessage("Cet utilisateur n'est pas sur le serveur.").queue();
                return;
            }
            
            // Générer et envoyer des offres
            contractManager.sendContractOffers(targetUser);
            
            e.getHook().sendMessage("Des offres de contrat ont été générées et envoyées à **" +
                                        targetMember.getEffectiveName() + "**.").queue();
        } catch (Exception ex) {
            e.getHook().sendMessage("Une erreur est survenue : " + ex.getMessage()).queue();
        }
    }
    
    /**
     * Vérifie si un membre a des permissions d'administration.
     * @param member Le membre à vérifier
     * @return true si le membre a des permissions d'administration
     */
    private boolean hasAdminPermission(Member member) {
        if (member == null) return false;
        return member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR) || member.isOwner();
    }
}