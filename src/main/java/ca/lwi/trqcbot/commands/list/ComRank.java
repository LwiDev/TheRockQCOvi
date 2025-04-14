package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.reputation.ReputationManager;
import ca.lwi.trqcbot.utils.FontUtils;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ComRank extends Command {

    private final String channelId;

    public ComRank() {
        super("rank", "Affichez votre rang actuel sur le serveur");
        setDefaultPermissions(DefaultMemberPermissions.ENABLED);
        addOption(OptionType.USER, "membre", "Utilisateur à rechercher", false);

        Dotenv dotenv = Dotenv.load();
        this.channelId = dotenv.get("CHANNEL_QG_DES_STATS_ID");
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (channelId == null || !Objects.requireNonNull(e.getChannelId()).equalsIgnoreCase(channelId)) {
            e.reply("> 🛑 Cette commande ne peut être utilisée ici.\n" +
                    "> Dirige-toi vers le salon <#" + channelId + "> pour utiliser /rank.").setEphemeral(true).queue();
            return;
        }

        e.deferReply().queue();

        // Obtenir l'utilisateur cible (soit l'utilisateur mentionné, soit celui qui a exécuté la commande)
        OptionMapping memberOption = e.getOption("membre");
        User targetUser = memberOption != null ? memberOption.getAsUser() : e.getUser();
        Member targetMember = memberOption != null ? memberOption.getAsMember() : e.getMember();
        if (targetMember == null) {
            e.getHook().sendMessage("Impossible de trouver ce membre sur le serveur.").setEphemeral(true).queue();
            return;
        }

        String userId = targetUser.getId();
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Member member = e.getMember();
            if (member == null) return;
            String username = targetMember.getEffectiveName();
            String teamName = userData.getString("teamName");
            int roundPick = userData.getInteger("roundPick", 0);
            String rank = userData.getString("currentRank");
            long joinTimestamp = userData.getLong("joinDate");

            Document reputation = (Document) userData.get("reputation");
            if (reputation == null) reputation = new Document();
            int reputationScore = reputation.getInteger("reputationScore", 0);
            String reputationRank = ReputationManager.getReputationRank(reputationScore);

            // Formatage de la date
            Date joinDate = new Date(joinTimestamp);
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.FRENCH);
            String formattedDate = sdf.format(joinDate);

            // Récupérer les informations de l'équipe depuis la DB teams
            Document teamData = Main.getTeamManager().getTeamByName(teamName);
            Color teamColor = Color.GRAY;
            String logoPath = null;

            if (teamData != null) {
                String colorHex = teamData.getString("color");
                if (colorHex != null && !colorHex.isEmpty()) {
                    teamColor = Color.decode(colorHex);
                }
                logoPath = teamData.getString("logo");
            }

            String ordinal;
            if (roundPick == 1) {
                ordinal = "1";
            } else {
                ordinal = roundPick + "";
            }

            try {
                BufferedImage avatar = getUserAvatar(targetUser);
                ByteArrayOutputStream outputStream = generateModernPlayerCard(username, teamName, formattedDate, ordinal, rank, teamColor, logoPath, avatar, reputationScore, reputationRank);
                e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), username + "_rank.png")).queue();
            } catch (Exception ex) {
                e.getHook().sendMessage("Erreur lors de la génération de la carte de rang : " + ex.getMessage()).setEphemeral(true).queue();
            }
        } else {
            String notFoundMessage = memberOption != null ? "Impossible de trouver le profil de " + targetMember.getEffectiveName() + "." : "Impossible de trouver votre profil.";
            e.getHook().sendMessage(notFoundMessage).setEphemeral(true).queue();
        }
    }

    private ByteArrayOutputStream generateModernPlayerCard(String username, String teamName, String draftDate, String roundPick, String rank, Color teamColor, String logoPath, BufferedImage avatar, int reputationScore, String reputationRank) throws IOException {
        int width = 800;
        int height = 500;

        // Base image with team color to dark gradient background
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Configure antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Main background - use team color to black gradient
        Color darkTeamColor = getDarkerColor(teamColor);
        Color black = new Color(0, 0, 10);
        GradientPaint backgroundGradient = new GradientPaint(0, 0, darkTeamColor, 0, height, black);
        g2d.setPaint(backgroundGradient);
        g2d.fillRect(0, 0, width, height);

        // Draw logo in background if available
        try {
            if (logoPath != null && !logoPath.isEmpty()) {
                BufferedImage logo = loadImage(logoPath);
                int infoBarY = 180;
                int logoSize = Math.min(width, height) - 100;
                int logoX = (width - logoSize) / 2;
                int logoY = infoBarY + 40;
                float alpha = 0.15f;
                AlphaComposite alphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
                g2d.setComposite(alphaComposite);
                g2d.drawImage(logo, logoX, logoY, logoSize, logoSize, null);
                g2d.setComposite(AlphaComposite.SrcOver);
            }
        } catch (Exception e) {
            // Log error or handle exception
            System.err.println("Error loading logo: " + e.getMessage());
            e.printStackTrace();
        }

        // Draw header with user info
        drawHeader(g2d, username, rank, width, avatar, logoPath);

        // Info bar with team and draft pick
        int infoBarY = 180;
        drawInfoBar(g2d, width, infoBarY, teamName, roundPick);

        // Stats section using the provided team color
        drawInfosSection(g2d, width, infoBarY + 70, reputationScore, draftDate, teamColor, reputationRank);

        g2d.dispose();

        // Convert image to ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream;
    }

    private void drawHeader(Graphics2D g2d, String username, String rank, int width, BufferedImage avatar, String logoPath) {
        int logoSize = 80;
        int logoX = 60;
        int logoY = 50;
        try {
            if (logoPath != null && !logoPath.isEmpty()) {
                BufferedImage logo = loadImage(logoPath);
                BufferedImage circularLogo = new BufferedImage(logoSize, logoSize, BufferedImage.TYPE_INT_ARGB);
                Graphics2D logoG2d = circularLogo.createGraphics();
                logoG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                logoG2d.setClip(new Ellipse2D.Float(0, 0, logoSize, logoSize));
                logoG2d.drawImage(logo, 0, 0, logoSize, logoSize, null);
                logoG2d.dispose();
                g2d.drawImage(circularLogo, logoX, logoY, null);
            }
        } catch (Exception e) {
            System.err.println("Erreur lors du chargement du logo:");
            e.printStackTrace();
        }

        // Configurer les polices pour pouvoir calculer les dimensions
        Font usernameFont = FontUtils.calculateOptimalNameFont(g2d, username, 250, 60);
        Font rankFont = new Font("Arial", Font.BOLD, 24);
        FontMetrics usernameFontMetrics = g2d.getFontMetrics(usernameFont);
        FontMetrics rankFontMetrics = g2d.getFontMetrics(rankFont);
        int usernameWidth = usernameFontMetrics.stringWidth(username);
        int rankWidth = rankFontMetrics.stringWidth(rank);
        int maxTextWidth = Math.max(usernameWidth, rankWidth);
        int avatarSize = 90;
        int avatarY = 45;
        int totalContentWidth = avatarSize + 20 + maxTextWidth; // 20px d'espacement
        int avatarX = (width - totalContentWidth) / 2;

        // Créer l'avatar circulaire
        BufferedImage circularAvatar = new BufferedImage(avatarSize, avatarSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D avatarG2d = circularAvatar.createGraphics();
        avatarG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        avatarG2d.setClip(new Ellipse2D.Float(0, 0, avatarSize, avatarSize));
        avatarG2d.drawImage(avatar, 0, 0, avatarSize, avatarSize, null);
        avatarG2d.dispose();
        g2d.drawImage(circularAvatar, avatarX, avatarY, null);

        // Position du texte à droite de l'avatar
        int textX = avatarX + avatarSize + 20;
        int avatarCenterY = avatarY + (avatarSize / 2);
        int usernameHeight = usernameFontMetrics.getHeight();
        int rankHeight = rankFontMetrics.getHeight();
        int textSpacing = 10;
        int totalTextHeight = usernameHeight + textSpacing + rankHeight;
        int usernameY = avatarCenterY - (totalTextHeight / 2) + usernameFontMetrics.getAscent() + 7;

        // Dessiner le nom
        g2d.setColor(Color.WHITE);
        g2d.setFont(usernameFont);
        g2d.drawString(username, textX, usernameY);

        // Dessiner le grade sous le nom
        g2d.setFont(rankFont);
        int rankY = usernameY + textSpacing + rankFontMetrics.getAscent();
        g2d.drawString(rank, textX, rankY);
    }

    private void drawInfoBar(Graphics2D g2d, int width, int y, String teamName, String roundPick) {
        // Light gray background for info bar
        g2d.setColor(new Color(230, 230, 230));
        g2d.fillRect(0, y, width, 40);

        // Format: "Team Name | #Pick" - all on one line and centered
        String infoText = teamName + " | #" + roundPick;

        // Draw text in black, centered
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 24));

        // Center the text
        FontMetrics metrics = g2d.getFontMetrics();
        int textWidth = metrics.stringWidth(infoText);
        int textX = (width - textWidth) / 2;

        g2d.drawString(infoText, textX, y + 27);
    }

    private void drawInfosSection(Graphics2D g2d, int width, int startY, int reputationScore, String draftDate, Color teamColor, String reputationRank) {
        g2d.setColor(new Color(40, 50, 60, 180));

        // Barre d'accent latérale
        g2d.setColor(Color.GRAY);
        g2d.fillRect(45, startY + 10, 5, 30);

        // Texte du titre
        g2d.setFont(new Font("Arial", Font.BOLD, 20));
        g2d.setColor(teamColor);
        g2d.drawString("INFORMATIONS", 60, startY + 30);

        // Stats list with more spacing
        String[] statNames = {"Repêché le", "Niveau", "Score"};
        String[] statValues = {
                String.valueOf(draftDate),
                String.valueOf(reputationRank),
                String.valueOf(reputationScore)
        };

        g2d.setFont(new Font("Segoe UI Emoji", Font.BOLD, 28));
        int lineHeight = 60;

        for (int i = 0; i < statNames.length; i++) {
            int y = startY + 75 + (i * lineHeight);

            // Stat name
            g2d.setColor(Color.WHITE);
            g2d.drawString(statNames[i], 60, y);

            // Stat value (right-aligned)
            String value = statValues[i];
            int valueWidth = g2d.getFontMetrics().stringWidth(value);
            g2d.setColor(Color.WHITE);
            g2d.drawString(value, width - 60 - valueWidth, y);

            // Separator line between stats
            if (i < statNames.length - 1) {
                g2d.setColor(new Color(60, 60, 80)); // Subtle separator
                g2d.drawLine(60, y + 15, width - 60, y + 15);
            }
        }
    }

    public static BufferedImage getUserAvatar(User user) throws IOException, URISyntaxException {
        URLConnection connection = new URI(user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl()).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "bot emily-bot");
        BufferedImage profileImg;
        try {
            profileImg = ImageIO.read(connection.getInputStream());
        } catch (Exception ignored) {
            profileImg = ImageIO.read(Objects.requireNonNull(ComRank.class.getClassLoader().getResource("default_profile.jpg")));
        }
        return profileImg;
    }

    private BufferedImage loadImage(String imagePath) throws IOException, URISyntaxException {
        if (imagePath == null || imagePath.isEmpty()) throw new IOException("Le chemin de l'image est vide ou null");
        BufferedImage image;
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            URI uri = new URI(imagePath);
            URLConnection connection = uri.toURL().openConnection();
            connection.setRequestProperty("User-Agent", "bot emily-bot");
            image = ImageIO.read(connection.getInputStream());
        } else {
            image = ImageIO.read(new File(imagePath));
        }
        if (image == null) throw new IOException("Impossible de charger l'image: " + imagePath);
        return image;
    }

    private Color getDarkerColor(Color original) {
        float[] hsbValues = Color.RGBtoHSB(original.getRed(), original.getGreen(), original.getBlue(), null);
        hsbValues[2] = Math.max(0, hsbValues[2] * (float) 0.5);
        return Color.getHSBColor(hsbValues[0], hsbValues[1], hsbValues[2]);
    }
}