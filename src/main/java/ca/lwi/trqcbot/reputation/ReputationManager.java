package ca.lwi.trqcbot.reputation;

import org.bson.Document;

public class ReputationManager {

    public static int calculateReputation(Document userData, int messagesCount) {
        int reputationScore = 0;

        // Bonus de vétéran
        boolean isVeteran = userData.getString("currentRank").contains("Vétéran");
        if (isVeteran) reputationScore += 15;

        // Pénalité d'inactivité
        reputationScore = getInactiveScore(userData, reputationScore);

        // Bonus d'interactions
        int reputationActionBonus = calculateReputationActionBonus(userData);
        reputationScore += reputationActionBonus;

        // Bonus de messages quotidiens
        int dailyMessages = userData.getInteger("dailyMessagesCount", 0);
        int avgDailyMessages = userData.getInteger("avgDailyMessages", dailyMessages);
        int messageWeightedAvg = calculateWeightedAverage(dailyMessages, avgDailyMessages);

        reputationScore = applyDailyActivityFactor(reputationScore, messageWeightedAvg, messagesCount);

        // Bonus d'activité vocale
        int voiceActivityBonus = calculateVoiceActivityBonus(userData);
        reputationScore += voiceActivityBonus;

        // Facteur basé sur le nombre total de messages - progression graduelle
        if (messagesCount <= 30) {
            reputationScore = (int) (reputationScore * 0.7);
        } else if (messagesCount <= 100) {
            reputationScore = (int) (reputationScore * 0.85);
        } else if (messagesCount <= 300) {
            reputationScore = (int) (reputationScore * 0.95);
        } else if (messagesCount <= 600) {
            reputationScore = (int) (reputationScore * 1.05);
        } else {
            reputationScore = (int) (reputationScore * 1.15);
        }

        return Math.max(0, Math.min(reputationScore, 100));
    }

    private static int calculateWeightedAverage(int dailyMessages, int avgDailyMessages) {
        return (int) Math.round(dailyMessages * 0.3 + avgDailyMessages * 0.7);
    }

    private static int applyDailyActivityFactor(int reputationScore, int weightedDailyMessages, int totalMessages) {
        // La pénalité/bonus est moins sévère et prend en compte l'historique
        if (weightedDailyMessages > 60) {
            reputationScore = (int) (reputationScore * 1.08);
        } else if (weightedDailyMessages > 30) {
            reputationScore = (int) (reputationScore * 1.03);
        } else if (weightedDailyMessages < 10 && totalMessages < 100) {
            // Pénalité significative seulement pour les nouveaux utilisateurs peu actifs
            reputationScore = (int) (reputationScore * 0.85);
        } else if (weightedDailyMessages < 15 && totalMessages < 300) {
            // Pénalité légère pour les utilisateurs modérément établis
            reputationScore = (int) (reputationScore * 0.92);
        } else if (weightedDailyMessages < 15) {
            // Pénalité très légère pour les utilisateurs bien établis
            reputationScore = (int) (reputationScore * 0.97);
        }
        return reputationScore;
    }

    private static int calculateReputationActionBonus(Document userData) {
        int bonus = 0;
        int responses = userData.getInteger("responsesCount", 0);
        int tags = userData.getInteger("tagsCount", 0);

        // Points pour les réponses, avec plafonnement progressif
        if (responses <= 10) {
            bonus += responses;
        } else if (responses <= 50) {
            bonus += (int) (10 + (responses - 10) * 0.7);
        } else {
            bonus += (int) (10 + 40 * 0.7 + (responses - 50) * 0.4);
        }

        // Points pour les tags, valorisés mais également plafonnés
        if (tags <= 10) {
            bonus += tags * 2;
        } else if (tags <= 30) {
            bonus += (int) (20 + (tags - 10) * 1.5);
        } else {
            bonus += (int) (20 + 20 * 1.5 + (tags - 30) * 0.8);
        }

        return bonus;
    }

    private static int calculateVoiceActivityBonus(Document userData) {
        int totalVoiceMinutes = userData.getInteger("totalVoiceMinutes", 0);
        int dailyVoiceMinutes = userData.getInteger("dailyVoiceMinutes", 0);
        int voiceDaysActive = userData.getInteger("voiceDaysActive", 0);

        int voiceBonus = 0;

        // Bonus pour l'activité quotidienne avec une courbe de rendement décroissant
        if (dailyVoiceMinutes >= 120) {
            voiceBonus += 8; // Maximum pour 2h ou plus
        } else if (dailyVoiceMinutes >= 60) {
            voiceBonus += 5; // Pour 1h - 2h
        } else if (dailyVoiceMinutes >= 20) {
            voiceBonus += 2; // Pour 20min - 1h
        }

        // Bonus pour la régularité (nombre de jours avec activité vocale)
        if (voiceDaysActive >= 20) {
            voiceBonus += 7; // Utilisateur très régulier
        } else if (voiceDaysActive >= 10) {
            voiceBonus += 4; // Utilisateur régulier
        } else if (voiceDaysActive >= 5) {
            voiceBonus += 2; // Utilisateur occasionnel
        }

        // Bonus pour l'activité totale avec plafonnement
        if (totalVoiceMinutes > 6000) {      // 100h+
            voiceBonus += 10;
        } else if (totalVoiceMinutes > 3000) { // 50h+
            voiceBonus += 7;
        } else if (totalVoiceMinutes > 1200) { // 20h+
            voiceBonus += 4;
        } else if (totalVoiceMinutes > 300) {  // 5h+
            voiceBonus += 2;
        }

        // Plafonner à 20 points pour éviter que ce soit over-powered
        return Math.min(20, voiceBonus);
    }

    private static int getInactiveScore(Document userData, int reputationScore) {
        long lastActiveTimestamp = userData.containsKey("lastActive") ? userData.getLong("lastActive") : userData.getLong("joinDate");
        long daysInactive = (System.currentTimeMillis() - lastActiveTimestamp) / (1000 * 60 * 60 * 24);

        if (daysInactive > 30) {
            reputationScore -= 20; // Très longue inactivité
        } else if (daysInactive > 21) {
            reputationScore -= 15; // Longue inactivité
        } else if (daysInactive > 14) {
            reputationScore -= 10; // Inactivité moyenne
        } else if (daysInactive > 7) {
            reputationScore -= 5;  // Courte inactivité
        }
        return reputationScore;
    }

    public static String getReputationRank(int reputationScore) {
        if (reputationScore > 90) {
            return "🌟 Légende";
        } else if (reputationScore > 60) {
            return "🔥 Respecté";
        } else if (reputationScore > 40) {
            return "🗣️ Connu";
        } else if (reputationScore > 20) {
            return "👋 Présent";
        } else {
            return "💨 Invisible";
        }
    }
}
