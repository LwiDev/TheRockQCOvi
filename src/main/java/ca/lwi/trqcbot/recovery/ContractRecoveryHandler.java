package ca.lwi.trqcbot.recovery;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.contracts.ContractsManager;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ContractRecoveryHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContractRecoveryHandler.class);
    private final String guildId;
    @Getter
    private final ScheduledExecutorService scheduler;
    private final ContractsManager contractsManager;

    public ContractRecoveryHandler() {
        this.guildId = Dotenv.load().get("GUILD_ID");
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.contractsManager = Main.getContractsManager();
    }

    public void init(Guild guild) {
        LOGGER.info("Vérification des membres sans contrat...");
        if (guild == null) {
            LOGGER.error("Guild non trouvée avec l'ID: {}", guildId);
            return;
        }
        scheduler.schedule(() -> recoverMembersWithoutContracts(guild), 5, TimeUnit.SECONDS);
    }

    private void recoverMembersWithoutContracts(Guild guild) {
        LOGGER.info("Recherche des membres sans contrat dans la base de données...");
        // Récupérer tous les membres du serveur
        guild.loadMembers().onSuccess(members -> {
            List<Member> membersWithoutContracts = new ArrayList<>();

            for (Member member : members) {
                if (member.getUser().isBot()) continue;
                Document userData = Main.getRankManager().getUserData(member.getId());
                boolean hasActiveContract = false;
                if (userData != null && userData.containsKey("contract")) {
                    Document contract = (Document) userData.get("contract");
                    hasActiveContract = contract != null && "active".equals(contract.getString("status"));
                }
                if (!hasActiveContract) {
                    membersWithoutContracts.add(member);
                    LOGGER.info("Membre sans contrat trouvé: {} ({})", member.getEffectiveName(), member.getId());
                }
            }

            LOGGER.info("Nombre total de membres à traiter: {}", membersWithoutContracts.size());
            if (!membersWithoutContracts.isEmpty()) {
                processMembersWithoutContractsWithDelay(membersWithoutContracts, 0);
            } else {
                LOGGER.info("Tous les membres ont déjà un contrat actif.");
            }
        });
    }

    private void processMembersWithoutContractsWithDelay(List<Member> members, int index) {
        if (index >= members.size()) {
            LOGGER.info("Traitement des contrats terminé. {} contrats créés.", members.size());
            return;
        }
        Member member = members.get(index);
        try {
            Document userData = Main.getRankManager().getUserData(member.getId());
            boolean hasActiveContract = false;
            if (userData != null && userData.containsKey("contract")) {
                Document contract = (Document) userData.get("contract");
                hasActiveContract = contract != null && "active".equals(contract.getString("status"));
            }
            if (!hasActiveContract) {
                LOGGER.info("Création d'un contrat d'entrée pour: {}", member.getEffectiveName());
                createEntryContractWithJoinDate(member);
            } else {
                LOGGER.info("L'utilisateur {} a déjà un contrat actif - aucun contrat créé", member.getEffectiveName());
            }
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la création du contrat pour {}: {}", member.getEffectiveName(), e.getMessage(), e);
        }
        scheduler.schedule(() -> processMembersWithoutContractsWithDelay(members, index + 1), 2, TimeUnit.SECONDS);
    }

    /**
     * Crée un contrat d'entrée en prenant en compte la date d'arrivée du membre
     * @param member Le membre pour lequel créer un contrat
     */
    private void createEntryContractWithJoinDate(Member member) {
        Document contract = contractsManager.generateEntryContract(member);
        if (contract == null) {
            LOGGER.error("Échec de la création du contrat d'entrée pour: {}", member.getEffectiveName());
            return;
        }
        LOGGER.info("Contrat d'entrée créé pour {} avec l'équipe {}", member.getEffectiveName(), contract.getString("teamName"));
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