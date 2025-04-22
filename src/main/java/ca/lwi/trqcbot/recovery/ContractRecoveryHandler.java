package ca.lwi.trqcbot.recovery;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.contracts.ContractManager;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ContractRecoveryHandler extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContractRecoveryHandler.class);
    private final String guildId;
    private final ScheduledExecutorService scheduler;
    private final ContractManager contractManager;

    private static final int ENTRY_CONTRACT_DURATION_DAYS = 12; // 3 ans

    public ContractRecoveryHandler() {
        this.guildId = Dotenv.load().get("GUILD_ID");
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.contractManager = Main.getContractManager();
    }

    @Override
    public void onReady(ReadyEvent e) {
        LOGGER.info("Bot en ligne, vérification des membres sans contrats...");
        Guild guild = e.getJDA().getGuildById(guildId);
        if (guild == null) {
            LOGGER.error("Guild non trouvée avec l'ID: {}", guildId);
            return;
        }
        scheduler.schedule(() -> recoverMembersWithoutContracts(guild), 5, TimeUnit.SECONDS);
    }

    private void recoverMembersWithoutContracts(Guild guild) {
        LOGGER.info("Recherche des membres sans contrats dans la base de données...");

        // Récupérer tous les membres du serveur
        guild.loadMembers().onSuccess(members -> {
            List<Member> membersWithoutContracts = new ArrayList<>();

            for (Member member : members) {
                if (member.getUser().isBot()) continue;

                // Vérifier si l'utilisateur a un contrat actif
                Document userData = Main.getRankManager().getUserData(member.getId());
                boolean hasActiveContract = false;

                if (userData != null && userData.containsKey("contract")) {
                    Document contract = (Document) userData.get("contract");
                    hasActiveContract = contract != null && contract.getBoolean("active", false);
                }

                if (!hasActiveContract) {
                    membersWithoutContracts.add(member);
                    LOGGER.info("Membre sans contrat trouvé: {} ({})", member.getEffectiveName(), member.getId());
                }
            }

            LOGGER.info("Nombre total de membres à traiter: {}", membersWithoutContracts.size());
            if (!membersWithoutContracts.isEmpty()) {
                processMembersWithoutContractsWithDelay(guild, membersWithoutContracts, 0);
            } else {
                LOGGER.info("Tous les membres ont déjà un contrat actif.");
            }
        });
    }

    private void processMembersWithoutContractsWithDelay(Guild guild, List<Member> members, int index) {
        if (index >= members.size()) {
            LOGGER.info("Traitement des contrats terminé. {} contrats créés.", members.size());
            return;
        }

        Member member = members.get(index);

        try {
            LOGGER.info("Création d'un contrat d'entrée pour: {}", member.getEffectiveName());
            createEntryContractWithJoinDate(member);
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la création du contrat pour {}: {}", member.getEffectiveName(), e.getMessage(), e);
        }

        // Traiter le prochain membre après un délai pour éviter la surcharge
        scheduler.schedule(() -> processMembersWithoutContractsWithDelay(guild, members, index + 1), 2, TimeUnit.SECONDS);
    }

    /**
     * Crée un contrat d'entrée en prenant en compte la date d'arrivée du membre
     * @param member Le membre pour lequel créer un contrat
     */
    private void createEntryContractWithJoinDate(Member member) {
        String userId = member.getId();
        Document userData = Main.getRankManager().getUserData(userId);

        // Obtenir la date d'arrivée
        Date joinDate;
        if (userData != null && userData.containsKey("joinDate")) {
            joinDate = userData.getDate("joinDate");
        } else {
            // Si la date n'est pas disponible dans la DB, utiliser la date d'arrivée JDA
            joinDate = Date.from(member.getTimeJoined().toInstant());
        }

        // Créer ou mettre à jour l'utilisateur si nécessaire
        boolean newUser = false;
        if (userData == null) {
            userData = new Document()
                    .append("userId", userId)
                    .append("joinDate", joinDate)
                    .append("reputation", new Document("reputationScore", 0)); // Score de réputation par défaut
            newUser = true;
        } else if (!userData.containsKey("joinDate")) {
            userData.append("joinDate", joinDate);
        }

        // Calculer le temps écoulé depuis l'arrivée en jours
        long daysSinceJoin = (System.currentTimeMillis() - joinDate.getTime()) / (1000 * 60 * 60 * 24);

        // Obtenir une équipe aléatoire
        Document randomTeam = getRandomTeam();
        if (randomTeam == null) {
            LOGGER.error("Aucune équipe trouvée pour le contrat d'entrée de l'utilisateur: {}", userId);
            return;
        }

        String teamName = randomTeam.getString("name");

        // Génération d'un salaire aléatoire
        int minSalary = 775000; // 775k minimum
        int maxSalary = 950000; // 950k maximum
        int salary = minSalary + new Random().nextInt(maxSalary - minSalary + 1);

        // Date actuelle pour le début du contrat
        Date startDate = new Date();

        // Calculer la durée restante du contrat
        int remainingDays = ENTRY_CONTRACT_DURATION_DAYS - (int)Math.min(daysSinceJoin, ENTRY_CONTRACT_DURATION_DAYS);
        remainingDays = Math.max(1, remainingDays); // Au moins 1 jour de contrat

        // Date d'expiration
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.DAY_OF_MONTH, remainingDays);
        Date expiryDate = calendar.getTime();

        // Créer le document de contrat
        Document contract = new Document()
                .append("teamName", teamName)
                .append("salary", salary)
                .append("years", 3) // 3 ans = 12 jours (nominal)
                .append("startDate", startDate)
                .append("expiryDate", expiryDate)
                .append("type", "entry") // contrat d'entrée
                .append("contractType", "2 Way") // les contrats d'entrée sont toujours à deux volets
                .append("active", true);

        // Mettre à jour l'utilisateur avec le contrat
        userData.append("teamName", teamName)
                .append("contractStatus", "signed")
                .append("contract", contract);

        // Enregistrer ou mettre à jour dans la base de données
        if (newUser) {
            Main.getMongoConnection().getDatabase().getCollection("users").insertOne(userData);
        } else {
            Main.getMongoConnection().getDatabase().getCollection("users").replaceOne(
                    new Document("_id", userData.getObjectId("_id")), userData);
        }

        LOGGER.info("Contrat d'entrée créé pour {} avec l'équipe {} pour {} jours restants",
                member.getEffectiveName(), teamName, remainingDays);

        // Envoyer un message privé à l'utilisateur
        double salaryInM = salary / 1000000.0;
        member.getUser().openPrivateChannel().queue(channel -> {
            channel.sendMessage("🏒 **Contrat d'entrée attribué automatiquement**\n\n" +
                    "Suite à une mise à jour du système, vous avez reçu un contrat d'entrée avec les **" + teamName + "** " +
                    "pour un salaire annuel de $" + String.format("%.3fM", salaryInM) + ".\n\n" +
                    "Ce contrat est à deux volets, ce qui signifie que vous pourriez être envoyé dans les ligues mineures.\n\n" +
                    "Utilisez la commande `/contrat voir` pour consulter les détails de votre contrat.\n\n" +
                    "Bonne chance pour votre carrière !").queue();
        });
    }

    /**
     * Obtient une équipe aléatoire de la base de données.
     * @return Document de l'équipe
     */
    private Document getRandomTeam() {
        List<Document> teams = new ArrayList<>();
        Main.getMongoConnection().getDatabase().getCollection("teams").find().into(teams);

        if (teams.isEmpty()) {
            return null;
        }

        return teams.get(new Random().nextInt(teams.size()));
    }

    /**
     * Méthode pour forcer la récupération de tous les membres sans contrats
     * Utile pour des tests ou des récupérations manuelles
     */
    public void forceContractRecovery(SlashCommandInteractionEvent e) {
        LOGGER.info("Force la récupération des membres sans contrats");
        Guild guild = e.getJDA().getGuildById(guildId);
        if (guild != null) {
            recoverMembersWithoutContracts(guild);
            e.getHook().sendMessage("✅ Processus de récupération des contrats démarré pour tous les membres sans contrat actif.")
                    .setEphemeral(true).queue();
        } else {
            e.getHook().sendMessage("❌ Erreur: Serveur non trouvé.").setEphemeral(true).queue();
        }
    }

    /**
     * Méthode pour arrêter proprement le scheduler quand le bot s'arrête
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}