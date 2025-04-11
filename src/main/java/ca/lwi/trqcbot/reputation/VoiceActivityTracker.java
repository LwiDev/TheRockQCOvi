package ca.lwi.trqcbot.reputation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class VoiceActivityTracker {
    private final Map<String, Long> voiceJoinTimestamps = new HashMap<>();
    private final Map<String, Integer> voiceTimeAccumulated = new HashMap<>();

    // Méthode principale pour suivre l'activité vocale
    public void trackVoiceActivity(Member member, AudioChannel oldChannel, AudioChannel newChannel) {
        if (member == null) return;
        String userId = member.getId();

        // Cas 1: L'utilisateur quitte un canal vocal
        if (oldChannel != null && newChannel == null) {
            processVoiceLeave(userId);
            return;
        }

        // Cas 2: L'utilisateur rejoint un canal vocal
        if (oldChannel == null && newChannel != null) {
            if (canTrackUser(member)) voiceJoinTimestamps.put(userId, System.currentTimeMillis());
            return;
        }

        // Cas 3: L'utilisateur change d'état (muet/non-muet) ou de canal
        boolean wasTracking = voiceJoinTimestamps.containsKey(userId);
        boolean shouldTrackNow = canTrackUser(member);

        if (!wasTracking && shouldTrackNow) {
            // L'utilisateur vient de se démuter, commencer à suivre
            voiceJoinTimestamps.put(userId, System.currentTimeMillis());
        } else if (wasTracking && !shouldTrackNow) {
            // L'utilisateur vient de se mettre en sourdine, arrêter le suivi
            processVoiceLeave(userId);
        }
    }

    // Vérifier si l'utilisateur est dans un état où on peut suivre son temps vocal
    private boolean canTrackUser(Member member) {
        if (member.getVoiceState() == null) return false;
        return member.getVoiceState().inAudioChannel() && !member.getVoiceState().isSelfMuted() && !member.getVoiceState().isGuildMuted();
    }

    // Traiter la sortie d'un canal vocal ou la mise en sourdine
    private int processVoiceLeave(String userId) {
        Long joinTime = voiceJoinTimestamps.remove(userId);
        if (joinTime == null) return 0;

        long timeSpentMs = System.currentTimeMillis() - joinTime;
        int minutes = Math.max(1, (int) (timeSpentMs / (1000 * 60))); // Au moins 1 minute pour éviter les 0

        // Accumuler le temps
        int currentAccumulated = voiceTimeAccumulated.getOrDefault(userId, 0);
        voiceTimeAccumulated.put(userId, currentAccumulated + minutes);

        return minutes;
    }

    // Récupérer et réinitialiser le temps accumulé
    public int getAndResetVoiceTime(String userId) {
        int time = voiceTimeAccumulated.getOrDefault(userId, 0);
        voiceTimeAccumulated.put(userId, 0);
        return time;
    }

    // Obtenir le temps accumulé sans réinitialiser
    public int getVoiceTime(String userId) {
        return voiceTimeAccumulated.getOrDefault(userId, 0);
    }

    // Obtenir la liste des utilisateurs actuellement suivis
    public Set<String> getActiveUsers() {
        return new HashSet<>(voiceJoinTimestamps.keySet());
    }
}