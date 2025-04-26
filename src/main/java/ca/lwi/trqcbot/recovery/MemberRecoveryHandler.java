package ca.lwi.trqcbot.recovery;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.draft.DraftMessageHandler;
import com.mongodb.client.model.UpdateOptions;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemberRecoveryHandler extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemberRecoveryHandler.class);
    private final String guildId;
    private final DraftMessageHandler draftHandler;
    private final ScheduledExecutorService scheduler;

    public MemberRecoveryHandler() {
        this.guildId = Dotenv.load().get("GUILD_ID");
        this.draftHandler = new DraftMessageHandler();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void init(Guild guild) {
        LOGGER.info("Vérification des membres non enregistrés...");
        if (guild == null) {
            LOGGER.error("Guild non trouvée avec l'ID: {}", guildId);
            return;
        }
        scheduler.schedule(() -> recoverMissingMembers(guild), 3, TimeUnit.SECONDS);
        updateLastOnlineTime();
    }

    private void recoverMissingMembers(Guild guild) {
        LOGGER.info("Recherche des membres non enregistrés dans la base de données...");

        // Récupérer tous les membres du serveur
        guild.loadMembers().onSuccess(members -> {
            List<Member> unregisteredMembers = new ArrayList<>();

            for (Member member : members) {
                if (member.getUser().isBot()) continue;
                Document userData = Main.getMongoConnection().getDatabase().getCollection("users").find(new Document("userId", member.getId())).first();
                if (userData == null || !userData.containsKey("teamName")) {
                    unregisteredMembers.add(member);
                    LOGGER.info("Membre non enregistré trouvé: {} ({})", member.getEffectiveName(), member.getId());
                }
            }
            LOGGER.info("Nombre total de membres à traiter: {}", unregisteredMembers.size());
            if (!unregisteredMembers.isEmpty()) {
                processUnregisteredMembersWithDelay(guild, unregisteredMembers, 0);
            } else {
                LOGGER.info("Tous les membres sont déjà enregistrés dans la base de données.");
            }
        });
    }

    private void processUnregisteredMembersWithDelay(Guild guild, List<Member> members, int index) {
        if (index >= members.size()) {
            LOGGER.info("Traitement des membres terminé. {} membres récupérés.", members.size());
            return;
        }

        Member member = members.get(index);

        try {
            LOGGER.info("Attribution d'une équipe pour le membre non enregistré: {}", member.getEffectiveName());
            draftHandler.createMessage(guild, member);
        } catch (Exception e) {
            LOGGER.error("Erreur lors du traitement du membre {}: {}", member.getEffectiveName(), e.getMessage(), e);
        }
        scheduler.schedule(() -> processUnregisteredMembersWithDelay(guild, members, index + 1), 2, TimeUnit.SECONDS);
    }

    private void updateLastOnlineTime() {
        Main.getMongoConnection().getDatabase().getCollection("data_history")
                .updateOne(
                        new Document("_id", "last_online"),
                        new Document("$set", new Document("timestamp", System.currentTimeMillis() / 1000)),
                        new UpdateOptions().upsert(true)
                );
        LOGGER.info("Temps de dernière connexion mis à jour");
    }

    /**
     * Méthode pour forcer la récupération de tous les membres non enregistrés
     * Utile pour des tests ou des récupérations manuelles
     */
    public void forceRecovery(SlashCommandInteractionEvent e) {
        LOGGER.info("Force la récupération des membres non enregistrés");
        recoverMissingMembers(Objects.requireNonNull(e.getJDA().getGuildById(guildId)));
        e.getHook().sendMessage("✅ Tous les membres non enregistrés ont bien été récupérés.").setEphemeral(true).queue();
    }

    /**
     * Méthode pour arrêter proprement le scheduler quand le bot s'arrête
     * À appeler dans la méthode de shutdown de votre bot
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