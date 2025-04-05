package src.ca.lwi.trqcbot.commands.list;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bson.Document;
import src.ca.lwi.trqcbot.Main;
import src.ca.lwi.trqcbot.commands.Command;

import java.awt.*;
import java.util.Objects;

public class ComRank extends Command {

    public ComRank() {
        super("rank", "Affichez votre rang actuel sur le serveur");
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        String userId = e.getUser().getId();
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            String rank = userData.getString("currentRank");
            int messageCount = userData.getInteger("messageCount", 0);
            boolean youtubeLinked = userData.getBoolean("youtubeLinked", false);
            int threshold = 100;
            EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Profil de " + e.getUser().getName())
                .setColor(getRankColor(rank))
                .addField("Rang", rank, true)
                .addField("Messages", messageCount + "/" + threshold, true)
                .addField("YouTube lié", youtubeLinked ? "Oui" : "Non", true)
                .setThumbnail(e.getUser().getEffectiveAvatarUrl())
                .setFooter(Main.getDiscordName(), Objects.requireNonNull(e.getGuild()).getIconUrl());
            e.replyEmbeds(embed.build()).queue();
        } else {
            e.reply("Impossible de trouver votre profil.").setEphemeral(true).queue();
        }
    }
    
    private Color getRankColor(String rank) {
        switch (rank) {
            case "Recrue":
                return Color.GRAY;
            case "Joueur":
                return Color.BLUE;
            case "Vétéran":
                return Color.ORANGE;
            default:
                return Color.WHITE;
        }
    }
}