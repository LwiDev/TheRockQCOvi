package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
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
import java.util.concurrent.TimeUnit;

public class ComRank extends Command {

    private static final Font TITLE_FONT = new Font("Arial", Font.BOLD, 60);
    private static final Font SUBTITLE_FONT = new Font("Arial", Font.BOLD, 16);
    private static final Font HEADER_FONT = new Font("Arial", Font.BOLD, 18);
    private static final Font DRAFT_CATEGORY_FONT = new Font("Arial", Font.BOLD, 22);
    private static final Font DRAFT_INFO_FONT = new Font("Arial", Font.BOLD, 34);
    private static final Font DRAFT_SUBINFO_FONT = new Font("Arial", Font.PLAIN, 16);
    private static final Color BACKGROUND_COLOR = new Color(10, 10, 10);
    private static final Color ACCENT_COLOR = new Color(0, 200, 200);
    private static final Color HEADER_DARK = new Color(15, 15, 15);

    public ComRank() {
        super("rank", "Affichez votre rang actuel sur le serveur");
        setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        e.deferReply().queue(); // Déferrer la réponse immédiatement pour éviter le timeout

        String userId = e.getUser().getId();
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Member member = e.getMember();
            if (member == null) return;
            String username = member.getEffectiveName();
            String teamName = userData.getString("teamName");
            int roundPick = userData.getInteger("roundPick", 0);
            String rank = userData.getString("currentRank");
            boolean youtubeLinked = userData.getBoolean("youtubeLinked", false);
            String youtubeUsername = userData.getString("youtubeUsername") != null ? userData.getString("youtubeUsername") : null;
            long joinTimestamp = userData.getLong("joinDate");
            int messageCount = userData.getInteger("messageCount", 0);

            long currentTime = System.currentTimeMillis();
            long daysOnServer = TimeUnit.MILLISECONDS.toDays(currentTime - joinTimestamp);

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
                BufferedImage avatar = getUserAvatar(e.getUser());
                ByteArrayOutputStream outputStream = generateModernPlayerCard(username, teamName, formattedDate, ordinal,
                        rank, youtubeLinked, youtubeUsername, teamColor, logoPath, avatar, messageCount, daysOnServer);
                e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), username + "_rank.png")).queue();
            } catch (Exception ex) {
                e.getHook().sendMessage("Erreur lors de la génération de la carte de rang : " + ex.getMessage()).queue();
                ex.printStackTrace();
            }
        } else {
            e.getHook().sendMessage("Impossible de trouver votre profil.").queue();
        }
    }

    private ByteArrayOutputStream generateModernPlayerCard(String username, String teamName, String draftDate, String roundPick, String rank, boolean youtubeLinked, String youtubeUsername, Color teamColor, String logoPath, BufferedImage avatar, int messageCount, long daysOnServer) throws IOException {
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
        Color darkTeamColor = getDarkerColor(teamColor, 0.5f); // Create darker version of team color
        Color black = new Color(0, 0, 10);
        GradientPaint backgroundGradient = new GradientPaint(
                0, 0, darkTeamColor,
                0, height, black
        );
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
        drawStatsSection(g2d, width, infoBarY + 70, messageCount, draftDate, daysOnServer, youtubeLinked, youtubeUsername, teamColor);

        g2d.dispose();

        // Convert image to ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream;
    }

    // Helper method to create a darker version of a color
    private Color getDarkerColor(Color original, float factor) {
        float[] hsbValues = Color.RGBtoHSB(
                original.getRed(),
                original.getGreen(),
                original.getBlue(),
                null
        );
        // Decrease brightness while keeping hue and saturation
        hsbValues[2] = Math.max(0, hsbValues[2] * factor); // brightness
        return Color.getHSBColor(hsbValues[0], hsbValues[1], hsbValues[2]);
    }

    // The rest of the methods remain unchanged
    private void drawHeader(Graphics2D g2d, String username, String rank, int width, BufferedImage avatar, String logoPath) {
        // Logo de l'équipe à gauche
        int logoSize = 80;
        int logoX = 60;
        int logoY = 50;

        // Charger le logo de l'équipe
        try {
            if (logoPath != null && !logoPath.isEmpty()) {
                BufferedImage logo = loadImage(logoPath);

                // Dessiner le logo en cercle
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
        Font usernameFont = new Font("Arial", Font.BOLD, 68);
        Font rankFont = new Font("Arial", Font.BOLD, 28);

        // Obtenir les métriques de police pour calculer les largeurs
        FontMetrics usernameFontMetrics = g2d.getFontMetrics(usernameFont);
        int usernameWidth = usernameFontMetrics.stringWidth(username);

        // Taille de l'avatar
        int avatarSize = 90;

        // Calculer la largeur totale des éléments à centrer (avatar + espace + texte)
        int totalContentWidth = avatarSize + 20 + usernameWidth; // 20px d'espacement

        // Position de l'avatar - maintenant centré avec le nom
        int avatarX = (width - totalContentWidth) / 2;
        int avatarY = 45;

        // Créer l'avatar circulaire
        BufferedImage circularAvatar = new BufferedImage(avatarSize, avatarSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D avatarG2d = circularAvatar.createGraphics();
        avatarG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        avatarG2d.setClip(new Ellipse2D.Float(0, 0, avatarSize, avatarSize));
        avatarG2d.drawImage(avatar, 0, 0, avatarSize, avatarSize, null);
        avatarG2d.dispose();

        // Dessiner l'avatar
        g2d.drawImage(circularAvatar, avatarX, avatarY, null);

        // Position du nom - à droite de l'avatar
        int textX = avatarX + avatarSize + 20; // 20px d'espace entre l'avatar et le nom

        // Dessiner le nom
        g2d.setColor(Color.WHITE);
        g2d.setFont(usernameFont);
        g2d.drawString(username, textX, 100);

        // Dessiner le rang sous le nom
        g2d.setFont(rankFont);
        g2d.drawString(rank, textX, 140);
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

    private void drawStatsSection(Graphics2D g2d, int width, int startY, int messageCount, String draftDate, long daysOnServer, boolean youtubeLinked, String youtubeUsername, Color teamColor) {
        g2d.setColor(new Color(40, 50, 60, 180)); // Couleur sombre semi-transparente

        // Barre d'accent latérale
        g2d.setColor(Color.GRAY);
        g2d.fillRect(45, startY + 10, 5, 30); // Barre verticale colorée plus visible

        // Texte du titre
        g2d.setFont(new Font("Arial", Font.BOLD, 20));
        g2d.setColor(teamColor);
        g2d.drawString("INFORMATIONS", 60, startY + 30);

        // Stats list with more spacing
        String[] statNames = {"Messages", "Repêché le", "Youtube"};
        String[] statValues = {
                String.valueOf(messageCount),
                String.valueOf(draftDate),
                youtubeLinked ? youtubeUsername + " | ✔" : "❌"
        };

        g2d.setFont(new Font("Arial Unicode MS", Font.BOLD, 28)); // Larger font for stats
        int lineHeight = 60; // More space between stats lines

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

    /**
     * Charge une image à partir d'un chemin qui peut être une URL ou un chemin de fichier local
     * @param imagePath Le chemin de l'image (URL ou fichier)
     * @return L'image chargée sous forme de BufferedImage
     * @throws IOException Si une erreur survient lors du chargement
     */
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
}