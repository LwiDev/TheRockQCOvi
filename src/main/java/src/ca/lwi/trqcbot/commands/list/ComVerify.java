package src.ca.lwi.trqcbot.commands.list;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bson.Document;
import src.ca.lwi.trqcbot.Main;
import src.ca.lwi.trqcbot.commands.Command;
import src.ca.lwi.trqcbot.ranks.RankManager;

public class ComVerify extends Command {

    public ComVerify() {
        super("verify", "Vérifiez votre statut d'abonnement à la chaîne TheRockQC");
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        RankManager rankManager = Main.getRankManager();
        Document userData = rankManager.getUserData(userId);
        if (userData != null && userData.getBoolean("youtubeLinked", false)) {
            String youtubeUsername = userData.getString("youtubeUsername");
            event.deferReply(true).queue();
            try {
                boolean isSubscribed = rankManager.checkYoutubeSubscription(youtubeUsername);
                if (isSubscribed) {
                    rankManager.promoteToVeteran(userId);
                    event.getHook().sendMessage("Félicitations ! Vous êtes abonné à TheRockQC. Vous êtes maintenant un Vétéran !").queue();
                } else {
                    event.getHook().sendMessage("Vous ne semblez pas être abonné à TheRockQC. Abonnez-vous pour devenir un Vétéran !").queue();
                }
            } catch (Exception e) {
                event.getHook().sendMessage("Erreur lors de la vérification: " + e.getMessage()).queue();
            }
        } else {
            event.reply("Vous devez d'abord lier votre compte YouTube avec `/link [username]`").setEphemeral(true).queue();
        }
    }
}