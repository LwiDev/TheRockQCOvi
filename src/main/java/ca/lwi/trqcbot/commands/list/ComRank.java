package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.reputation.ReputationManager;
import ca.lwi.trqcbot.utils.FontUtils;
import ca.lwi.trqcbot.utils.ImageUtils;
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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ComRank extends Command {

    private final String channelId;

    public ComRank() {
        super("rank", "Afficher votre rang actuel sur le serveur");
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

            Date joinDate;
            Object joinDateValue = userData.get("joinDate");
            if (joinDateValue instanceof Long) {
                joinDate = new Date((Long) joinDateValue);
            } else if (joinDateValue instanceof Date) {
                joinDate = (Date) joinDateValue;
            } else {
                joinDate = new Date();
            }

            Document reputation = (Document) userData.get("reputation");
            if (reputation == null) reputation = new Document();
            int reputationScore = reputation.getInteger("reputationScore", 0);
            String reputationRank = ReputationManager.getReputationRank(reputationScore);

            // Formatage de la date
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

            // Récupérer les informations du contrat
            // Ces valeurs seraient normalement extraites de la base de données
            // Pour l'exemple, nous utiliserons des valeurs fictives
            Document contractData = userData.get("contract", Document.class);
            int contractYears = 0;
            double contractSalary = 0.0;
            String contractType = "N/A";
            boolean hasNTC = false;
            boolean hasNMC = false;
            String ntcDetails = "";
            String nmcDetails = "";

            if (contractData != null) {
                contractYears = contractData.getInteger("years", 0);
                contractSalary = contractData.getInteger("salary").doubleValue();
                contractType = contractData.getString("type");
                hasNTC = contractData.getBoolean("hasNTC", false);
                hasNMC = contractData.getBoolean("hasNMC", false);
                ntcDetails = contractData.getString("ntcDetails");
                nmcDetails = contractData.getString("nmcDetails");
            }

            try {
                BufferedImage avatar = ImageUtils.getUserAvatar(targetUser);
                ByteArrayOutputStream outputStream = generateModernPlayerCard(username, teamName, formattedDate, ordinal, rank, teamColor, logoPath, avatar, reputationScore, reputationRank, contractYears, contractSalary, contractType, hasNTC, hasNMC, ntcDetails, nmcDetails);
                e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), username + "_rank.png")).queue();
            } catch (Exception ex) {
                e.getHook().sendMessage("Erreur lors de la génération de la carte de rang : " + ex.getMessage()).setEphemeral(true).queue();
            }
        } else {
            String notFoundMessage = memberOption != null ? "Impossible de trouver le profil de " + targetMember.getEffectiveName() + "." : "Impossible de trouver votre profil.";
            e.getHook().sendMessage(notFoundMessage).setEphemeral(true).queue();
        }
    }

    // Image du profil
    private ByteArrayOutputStream generateModernPlayerCard(String username, String teamName, String draftDate, String roundPick, String rank,
                                                           Color teamColor, String logoPath, BufferedImage avatar, int reputationScore,
                                                           String reputationRank, int contractYears, double contractSalary, String contractType,
                                                           boolean hasNTC, boolean hasNMC, String ntcDetails, String nmcDetails) throws IOException {
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
                BufferedImage logo = ImageUtils.loadImage(logoPath, 500);
                int infoBarY = 180;
                int logoSize = height - 100;
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

        // Divisez l'espace en deux colonnes pour les sections
        int columnWidth = (width - 120) / 2; // 60px de marge de chaque côté

        // Section Informations (à gauche)
        drawInfosSection(g2d, columnWidth, infoBarY + 70, reputationScore, draftDate, teamColor, reputationRank);

        // Section Contrat (à droite)
        drawContractSection(g2d, width, columnWidth, infoBarY + 70, contractYears, contractSalary, contractType, hasNTC, hasNMC, ntcDetails, nmcDetails, teamColor);

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
                BufferedImage logo = ImageUtils.loadImage(logoPath, 80);
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

//    private void drawInfosSection(Graphics2D g2d, int width, int startY, int reputationScore, String draftDate, Color teamColor, String reputationRank) {
//        g2d.setColor(new Color(40, 50, 60, 180));
//
//        // Barre d'accent latérale
//        g2d.setColor(Color.GRAY);
//        g2d.fillRect(45, startY + 10, 5, 30);
//
//        // Texte du titre
//        g2d.setFont(new Font("Arial", Font.BOLD, 20));
//        g2d.setColor(teamColor);
//        g2d.drawString("INFORMATIONS", 60, startY + 30);
//
//        // Stats list with more spacing
//        String[] statNames = {"Repêché le", "Niveau", "Score"};
//        String[] statValues = {
//                String.valueOf(draftDate),
//                String.valueOf(reputationRank),
//                reputationScore + "%"
//        };
//
//        g2d.setFont(new Font("Arial", Font.BOLD, 20));
//        int lineHeight = 60;
//        int rightMargin = 60;
//
//        FontMetrics fm = g2d.getFontMetrics();
//        int maxValueWidth = Math.max(fm.stringWidth(statValues[0]), fm.stringWidth(statValues[1]));
//
//        // Définir une largeur de barre raisonnable qui ne dépasse pas la largeur du texte le plus long
//        int barWidth = Math.min(180, maxValueWidth);
//
//        for (int i = 0; i < statNames.length; i++) {
//            int y = startY + 75 + (i * lineHeight);
//
//            // Stat name
//            g2d.setColor(Color.WHITE);
//            g2d.drawString(statNames[i], 60, y);
//
//            if (statNames[i].equals("Niveau")) {
//                String value = statValues[i];
//                int valueWidth = fm.stringWidth(value);
//                int valueX = width - rightMargin - valueWidth;
//                FontUtils.drawMixedEmojiText(g2d, value, valueX, y, 20, new Font("Arial", Font.BOLD, 20), Color.WHITE);
//            } else if (statNames[i].equals("Score")) {
//                // Barre de progression (alignée à droite, même largeur que le texte ci-dessus)
//                int barHeight = 20;
//                int barX = width - rightMargin - barWidth;
//                int barY = y - 15;
//
//                // Fond de la barre (gris foncé)
//                g2d.setColor(new Color(40, 40, 50));
//                g2d.fillRect(barX, barY, barWidth, barHeight);
//
//                // Partie remplie de la barre
//                Color scoreColor = ReputationManager.getReputationColorFromScore(reputationScore);
//                int fillWidth = (int)(barWidth * reputationScore / 100.0);
//                g2d.setColor(scoreColor);
//                g2d.fillRect(barX, barY, fillWidth, barHeight);
//
//                // Contour de la barre
//                g2d.setColor(new Color(70, 70, 80));
//                g2d.drawRect(barX, barY, barWidth, barHeight);
//
//                // Affichage du pourcentage à l'intérieur de la barre
//                String scoreText = reputationScore + "%";
//                Font scoreFont = new Font("Arial", Font.BOLD, 14);
//                g2d.setFont(scoreFont);
//                FontMetrics scoreFm = g2d.getFontMetrics();
//                int textWidth = scoreFm.stringWidth(scoreText);
//                int textX = barX + (barWidth - textWidth) / 2;
//                int textY = barY + ((barHeight - scoreFm.getHeight()) / 2) + scoreFm.getAscent();
//
//                g2d.setColor(Color.WHITE);
//                g2d.drawString(scoreText, textX, textY);
//
//                // Revenir à la police d'origine
//                g2d.setFont(new Font("Arial", Font.BOLD, 20));
//            } else {
//                String value = statValues[i];
//                int valueWidth = fm.stringWidth(value);
//                g2d.setColor(Color.WHITE);
//                g2d.drawString(value, width - rightMargin - valueWidth, y);
//            }
//
//            // Separator line between stats
//            if (i < statNames.length - 1) {
//                g2d.setColor(new Color(60, 60, 80)); // Subtle separator
//                g2d.drawLine(60, y + 15, width - 60, y + 15);
//            }
//        }
//    }

    private void drawInfosSection(Graphics2D g2d, int columnWidth, int startY, int reputationScore, String draftDate, Color teamColor, String reputationRank) {
        // Barre d'accent latérale et titre - garde la même position Y
        g2d.setColor(Color.GRAY);
        g2d.fillRect(60, startY + 10, 5, 30);

        g2d.setFont(new Font("Arial", Font.BOLD, 20));
        g2d.setColor(teamColor);
        g2d.drawString("INFORMATIONS", 75, startY + 30);

        // Stats list
        String[] statNames = {"Repêché le", "Niveau", "Score"};
        String[] statValues = {
                String.valueOf(draftDate),
                String.valueOf(reputationRank),
                reputationScore + "%"
        };

        g2d.setFont(new Font("Arial", Font.BOLD, 18));
        int lineHeight = 55; // Hauteur de ligne unifiée
        int leftMargin = 75;
        int firstLineY = startY + 80; // Position Y fixe pour la première ligne

        FontMetrics fm = g2d.getFontMetrics();
        int barWidth = 150;

        for (int i = 0; i < statNames.length; i++) {
            int y = firstLineY + (i * lineHeight); // Positions Y calculées à partir de la première ligne

            // Stat name
            g2d.setColor(Color.WHITE);
            g2d.drawString(statNames[i], leftMargin, y);

            if (statNames[i].equals("Niveau")) {
                String value = statValues[i];
                int valueWidth = fm.stringWidth(value);
                int valueX = 60 + columnWidth - valueWidth - 20;
                FontUtils.drawMixedEmojiText(g2d, value, valueX, y, 18, new Font("Arial", Font.BOLD, 18), Color.WHITE);
            } else if (statNames[i].equals("Score")) {
                int barHeight = 18;
                int barX = 60 + columnWidth - barWidth - 20;
                int barY = y - 15;

                g2d.setColor(new Color(40, 40, 50));
                g2d.fillRect(barX, barY, barWidth, barHeight);

                Color scoreColor = ReputationManager.getReputationColorFromScore(reputationScore);
                int fillWidth = (int)(barWidth * reputationScore / 100.0);
                g2d.setColor(scoreColor);
                g2d.fillRect(barX, barY, fillWidth, barHeight);

                g2d.setColor(new Color(70, 70, 80));
                g2d.drawRect(barX, barY, barWidth, barHeight);

                String scoreText = reputationScore + "%";
                Font scoreFont = new Font("Arial", Font.BOLD, 13);
                g2d.setFont(scoreFont);
                FontMetrics scoreFm = g2d.getFontMetrics();
                int textWidth = scoreFm.stringWidth(scoreText);
                int textX = barX + (barWidth - textWidth) / 2;
                int textY = barY + ((barHeight - scoreFm.getHeight()) / 2) + scoreFm.getAscent();

                g2d.setColor(Color.WHITE);
                g2d.drawString(scoreText, textX, textY);

                g2d.setFont(new Font("Arial", Font.BOLD, 18));
            } else {
                String value = statValues[i];
                int valueWidth = fm.stringWidth(value);
                g2d.setColor(Color.WHITE);
                g2d.drawString(value, 60 + columnWidth - valueWidth - 20, y);
            }

            // Separator line between stats
            if (i < statNames.length - 1) {
                g2d.setColor(new Color(60, 60, 80));
                g2d.drawLine(leftMargin, y + 15, 60 + columnWidth - 20, y + 15);
            }
        }
    }

    private void drawContractSection(Graphics2D g2d, int totalWidth, int columnWidth, int startY,
                                     int years, double salary, String contractType,
                                     boolean hasNTC, boolean hasNMC, String ntcDetails, String nmcDetails,
                                     Color teamColor) {
        int leftMargin = 60 + columnWidth + 40;

        // Barre d'accent latérale et titre - garde la même position Y
        g2d.setColor(Color.GRAY);
        g2d.fillRect(leftMargin, startY + 10, 5, 30);

        g2d.setFont(new Font("Arial", Font.BOLD, 20));
        g2d.setColor(teamColor);
        g2d.drawString("CONTRAT", leftMargin + 15, startY + 30);

        g2d.setFont(new Font("Arial", Font.BOLD, 18));
        int lineHeight = 55; // Hauteur de ligne unifiée
        int firstLineY = startY + 80; // Position Y fixe pour la première ligne - identique à l'autre section

        // Durée du contrat - première ligne à position fixe
        g2d.setColor(Color.WHITE);
        String yearsWithType = years + " an" + (years > 1 ? "s" : "") + " / " + contractType;
        drawContractLine(g2d, "Durée", yearsWithType, leftMargin, firstLineY);

        // Séparateur après la première ligne
        g2d.setColor(new Color(60, 60, 80));
        g2d.drawLine(leftMargin, firstLineY + 15, totalWidth - 60, firstLineY + 15);

        // Salaire du contrat (avec type) - deuxième ligne
        drawContractLine(g2d, "Salaire", Main.getContractsManager().getSalaryFormat(salary / 1000000.0), leftMargin, firstLineY + lineHeight);

        // Séparateur après la deuxième ligne
        g2d.setColor(new Color(60, 60, 80));
        g2d.drawLine(leftMargin, firstLineY + lineHeight + 15, totalWidth - 60, firstLineY + lineHeight + 15);

        // Clauses - troisième ligne
        String clausesText = "";
        if (hasNTC && hasNMC) {
            clausesText = "NTC & NMC";
        } else if (hasNTC) {
            clausesText = "NTC";
        } else if (hasNMC) {
            clausesText = "NMC";
        } else {
            clausesText = "Aucune";
        }

        drawContractLine(g2d, "Clauses", clausesText, leftMargin, firstLineY + (lineHeight * 2));

        // Détails des clauses si présentes
        if (hasNTC || hasNMC) {
            int detailsY = firstLineY + (lineHeight * 3);
            // Séparateur fin
            g2d.setColor(new Color(60, 60, 80, 100));
            g2d.drawLine(leftMargin + 15, detailsY - lineHeight/2, totalWidth - 75, detailsY - lineHeight/2);

            g2d.setFont(new Font("Arial", Font.ITALIC, 16));

            if (hasNTC) {
                g2d.setColor(new Color(220, 220, 220));
                g2d.drawString("NTC: " + ntcDetails, leftMargin + 15, detailsY);
                detailsY += lineHeight - 15;
            }

            if (hasNMC) {
                g2d.setColor(new Color(220, 220, 220));
                g2d.drawString("NMC: " + nmcDetails, leftMargin + 15, detailsY);
            }
        }
    }

    private void drawContractLine(Graphics2D g2d, String label, String value, int leftMargin, int y) {
        g2d.setColor(Color.WHITE);
        g2d.drawString(label, leftMargin + 15, y);

        FontMetrics fm = g2d.getFontMetrics();
        int valueWidth = fm.stringWidth(value);
        int rightEdge = 800 - 60; // largeur totale - marge droite

        g2d.drawString(value, rightEdge - valueWidth, y);
    }

    private Color getDarkerColor(Color original) {
        float[] hsbValues = Color.RGBtoHSB(original.getRed(), original.getGreen(), original.getBlue(), null);
        hsbValues[2] = Math.max(0, hsbValues[2] * (float) 0.5);
        return Color.getHSBColor(hsbValues[0], hsbValues[1], hsbValues[2]);
    }
}