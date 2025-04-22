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
        
        // Sous-commande pour voir son contrat actuel
        SubcommandData viewContract = new SubcommandData("voir", "Afficher les détails de votre contrat actuel");
        
        // Sous-commande pour demander de nouvelles offres (seulement si agent libre)
        SubcommandData requestOffers = new SubcommandData("demander", "Demander de nouvelles offres de contrat (agents libres seulement)");
        
        // Sous-commande pour les admins afin de générer des offres pour un utilisateur
        SubcommandData adminGenerate = new SubcommandData("generer", "Générer des offres de contrat pour un utilisateur (Admin seulement)")
                .addOption(OptionType.USER, "utilisateur", "L'utilisateur pour qui générer des offres", true);
        
        // Ajouter les sous-commandes
        addSubcommands(viewContract, requestOffers, adminGenerate);
        
        this.contractManager = Main.getContractManager();
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("Cette commande doit être utilisée sur un serveur.").setEphemeral(true).queue();
            return;
        }
        
        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("Une erreur est survenue.").setEphemeral(true).queue();
            return;
        }
        
        switch (subcommand) {
            case "voir":
                showCurrentContract(event);
                break;
            case "demander":
                requestNewOffers(event);
                break;
            case "generer":
                if (!hasAdminPermission(event.getMember())) {
                    event.reply("Vous n'avez pas la permission d'utiliser cette commande.").setEphemeral(true).queue();
                    return;
                }
                OptionMapping userOption = event.getOption("utilisateur");
                if (userOption == null) {
                    event.reply("Utilisateur non spécifié.").setEphemeral(true).queue();
                    return;
                }
                generateOffersForUser(event, userOption.getAsUser());
                break;
            default:
                event.reply("Sous-commande inconnue.").setEphemeral(true).queue();
        }
    }
    
    /**
     * Affiche les détails du contrat actuel de l'utilisateur.
     * @param event L'événement de commande
     */
    private void showCurrentContract(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        
        User user = event.getUser();
        Document userData = Main.getRankManager().getUserData(user.getId());
        
        if (userData == null) {
            event.getHook().sendMessage("Vous n'avez pas encore de profil sur le serveur.").queue();
            return;
        }
        
        String teamName = userData.getString("teamName");
        String contractStatus = userData.getString("contractStatus");
        
        if (contractStatus == null || contractStatus.equals("unsigned") || teamName.equals("Free Agent")) {
            event.getHook().sendMessage("Vous êtes actuellement un **Agent Libre**. Utilisez `/contrat demander` pour recevoir de nouvelles offres.").queue();
            return;
        }
        
        // Récupérer le contrat actif
        Document contract = Main.getMongoConnection().getDatabase().getCollection("contracts")
                .find(new Document("userId", user.getId()).append("active", true))
                .first();
        
        if (contract == null) {
            event.getHook().sendMessage("Aucun contrat actif trouvé. Il semble que vous soyez un Agent Libre. " +
                    "Utilisez `/contrat demander` pour recevoir de nouvelles offres.").queue();
            return;
        }
        
        // Formater les détails du contrat
        String teamNameFromContract = contract.getString("teamName");
        int years = contract.getInteger("years");
        double salary = contract.get("salary", Number.class).doubleValue() / 1000000.0; // Convertir en millions
        String contractType = contract.getString("type");
        Date startDate = contract.getDate("startDate");
        Date expiryDate = contract.getDate("expiryDate");
        
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMMM yyyy", Locale.FRENCH);
        
        StringBuilder response = new StringBuilder();
        response.append("📋 **Votre contrat actuel :**\n\n");
        response.append("🏒 **Équipe :** ").append(teamNameFromContract).append("\n");
        response.append("⏳ **Durée :** ").append(years).append(" an").append(years > 1 ? "s" : "").append("\n");
        response.append("💰 **Salaire annuel :** $").append(String.format("%.2fM", salary)).append("\n");
        response.append("📝 **Type de contrat :** ").append(contractType.equals("entry") ? "Contrat d'entrée" : "Standard").append("\n");
        response.append("🗓️ **Signé le :** ").append(dateFormat.format(startDate)).append("\n");
        response.append("⏰ **Expire le :** ").append(dateFormat.format(expiryDate)).append("\n\n");
        
        // Calculer le temps restant
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
        
        event.getHook().sendMessage(response.toString()).queue();
    }
    
    /**
     * Permet à un agent libre de demander de nouvelles offres de contrat.
     * @param event L'événement de commande
     */
    private void requestNewOffers(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        
        User user = event.getUser();
        Document userData = Main.getRankManager().getUserData(user.getId());
        
        if (userData == null) {
            // Nouvel utilisateur, créer un contrat d'entrée
            Document entryContract = contractManager.generateEntryContract(user.getId());
            if (entryContract != null) {
                String teamName = entryContract.getString("teamName");
                event.getHook().sendMessage("Bienvenue dans la ligue ! Vous avez signé un contrat d'entrée de 3 ans avec **" + 
                                            teamName + "**. Utilisez `/rank` pour voir votre profil.").queue();
            } else {
                event.getHook().sendMessage("Une erreur est survenue lors de la génération de votre contrat d'entrée.").queue();
            }
            return;
        }
        
        String teamName = userData.getString("teamName");
        String contractStatus = userData.getString("contractStatus");
        
        // Vérifier si l'utilisateur est un agent libre
        if (contractStatus == null || !contractStatus.equals("unsigned") && !teamName.equals("Free Agent")) {
            // Vérifier si le contrat actuel est expiré
            Document activeContract = Main.getMongoConnection().getDatabase().getCollection("contracts")
                    .find(new Document("userId", user.getId()).append("active", true))
                    .first();
            
            if (activeContract != null) {
                Date expiryDate = activeContract.getDate("expiryDate");
                if (expiryDate.after(new Date())) {
                    // Le contrat n'est pas encore expiré
                    long timeRemaining = expiryDate.getTime() - System.currentTimeMillis();
                    long daysRemaining = timeRemaining / (1000 * 60 * 60 * 24);
                    
                    event.getHook().sendMessage("Vous avez déjà un contrat en cours avec **" + teamName + 
                                                "**. Il expire dans " + daysRemaining + " jour(s).").queue();
                    return;
                }
            }
        }
        
        // Vérifier si des offres sont déjà en attente
        Document pendingOffers = Main.getMongoConnection().getDatabase().getCollection("contractOffers")
                .find(new Document("userId", user.getId()).append("status", "pending"))
                .first();
        
        if (pendingOffers != null) {
            event.getHook().sendMessage("Vous avez déjà des offres de contrat en attente. " +
                                        "Vérifiez vos messages privés ou contactez un administrateur si vous ne les trouvez pas.").queue();
            return;
        }
        
        // Générer de nouvelles offres
        contractManager.sendContractOffers(user);
        
        event.getHook().sendMessage("Des offres de contrat vous ont été envoyées en message privé. " +
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