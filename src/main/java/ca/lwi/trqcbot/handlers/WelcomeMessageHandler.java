package ca.lwi.trqcbot.handlers;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.utils.FontUtils;
import ca.lwi.trqcbot.utils.TeamSelectionResult;
import com.mongodb.client.MongoCollection;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WelcomeMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WelcomeMessageHandler.class);

    private final String welcomeChannel = "1356752351561781349";
    private MongoCollection<Document> teamsCollection;
    private MongoCollection<Document> draftHistoryCollection;
    private final Random random;
    private final int tradeChance;

    public WelcomeMessageHandler() {
        this.teamsCollection = Main.getMongoConnection().getDatabase().getCollection("teams");
        this.draftHistoryCollection = Main.getMongoConnection().getDatabase().getCollection("data_history");
        this.random = new Random();
        this.tradeChance = 5;
    }

    public void createMessage(Guild guild, Member member) {
        TextChannel channel = guild.getTextChannelById("1356752351561781349");
        if (channel == null) {
            System.out.println("Channel de bienvenue non trouvé: " + this.welcomeChannel);
            return;
        }

        try {
            TeamSelectionResult teamSelection = getNextTeam();
            if (teamSelection == null || teamSelection.team == null) {
                System.out.println("Erreur lors de la récupération d'une équipe");
                return;
            }

            Document teamDoc = teamSelection.team;
            String teamName = teamDoc.getString("name");
            String teamLogoUrl = teamDoc.getString("logo");
            String teamColorHex = teamDoc.getString("color");
            String teamId = teamDoc.getObjectId("_id").toString();
            boolean isTrade = teamSelection.isTrade;
            String originalTeam = teamSelection.originalTeamLogo;

            Document historyDoc = draftHistoryCollection.find(new Document("_id", "draft_tracker")).first();
            @SuppressWarnings("unchecked")
            List<String> usedTeams = (List<String>) historyDoc.get("usedTeams");
            if (!usedTeams.contains(teamId)) usedTeams.add(teamId);
            updateDraftHistory(teamId, usedTeams);

            byte[] imageBytes = generateDraftImage(
                    teamName,
                    teamLogoUrl,
                    teamColorHex,
                    member,
                    isTrade ? originalTeam : ""
            );

            int memberCount = guild.getMemberCount() - 1;
            channel.sendMessage("Les " + teamName + " sont fiers de choisir **" + member.getAsMention() + "** comme " + memberCount + "e choix au repêchage !")
                    .addFiles(FileUpload.fromData(imageBytes, member.getEffectiveName() + "_draft.png"))
                    .queue();

            Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                    new Document("userId", member.getId()),
                    new Document("$set", new Document("teamName", teamName).append("roundPick", memberCount))
            );
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de la génération du message {}: {}", "Globale", ex.getMessage());
            channel.sendMessage("Bienvenue à " + member.getAsMention() + " !").queue();
        }
    }

    public void createWelcomeMessageFromDb(Guild guild, Member member) {
        TextChannel channel = guild.getTextChannelById("1356752351561781349");
        if (channel == null) {
            System.out.println("Channel de bienvenue non trouvé: " + this.welcomeChannel);
            return;
        }

        try {
            Document userData = Main.getMongoConnection().getDatabase().getCollection("users").find(new Document("userId", member.getId())).first();
            if (userData == null) {
                channel.sendMessage("Bienvenue à " + member.getAsMention() + " ! (Aucune donnée d'équipe trouvée)").queue();
                return;
            }

            String teamName = userData.getString("teamName");
            if (teamName == null || teamName.isEmpty()) {
                channel.sendMessage("Bienvenue à " + member.getAsMention() + " ! (Aucune équipe attribuée)").queue();
                return;
            }

            // Récupérer les données de l'équipe
            Document teamDoc = teamsCollection.find(new Document("name", teamName)).first();
            if (teamDoc == null) {
                channel.sendMessage("Bienvenue à " + member.getAsMention() + " ! (Équipe non trouvée: " + teamName + ")").queue();
                return;
            }

            String teamDet = teamDoc.getString("det") != null ? teamDoc.getString("det") : "Les";
            String teamLogoUrl = teamDoc.getString("logo");
            String teamColorHex = teamDoc.getString("color");

            Integer roundPick = userData.getInteger("roundPick");
            if (roundPick == null) roundPick = guild.getMemberCount() - 1;
            byte[] imageBytes = generateDraftImage(
                    teamName,
                    teamLogoUrl,
                    teamColorHex,
                    member,
                    "",
                    roundPick
            );

            String verb = "sont";
            boolean plural = true;
            if (teamDet.equals("Le") || teamDet.equalsIgnoreCase("L'")) {
                verb = "est";
                plural = false;
            }
            channel.sendMessage(teamDet + " " + teamName + " " + verb + " fier" + (plural ? "s" : "") + " de choisir **" + member.getAsMention() + "** comme " + roundPick + "e choix au repêchage !")
                    .addFiles(FileUpload.fromData(imageBytes, member.getEffectiveName() + "_draft.png"))
                    .queue();

        } catch (Exception ex) {
            LOGGER.error("Erreur lors de la génération du message {}: {}", "Depuis DB", ex.getMessage());
            channel.sendMessage("Bienvenue à " + member.getAsMention() + " !").queue();
        }
    }

    private TeamSelectionResult getNextTeam() {
        // Récupérer l'historique de draft
        Document historyDoc = draftHistoryCollection.find(new Document("_id", "draft_tracker")).first();
        if (historyDoc == null) {
            initializeDraftHistory();
            historyDoc = draftHistoryCollection.find(new Document("_id", "draft_tracker")).first();
        }

        String lastTeamId = historyDoc.getString("lastTeamId");
        @SuppressWarnings("unchecked")
        List<String> usedTeams = (List<String>) historyDoc.get("usedTeams");
        int totalTeams = historyDoc.getInteger("totalTeams");

        // Vérifier si c'est un trade
        boolean isTrade = random.nextDouble() * 100 < this.tradeChance;
        String originalTeamLogo = "";

        if (isTrade) {
            // Si c'est un échange, trouver une équipe aléatoire différente de la dernière
            List<Document> allTeamsExceptLast = new ArrayList<>();
            for (Document team : teamsCollection.find()) {
                String teamId = team.getObjectId("_id").toString();
                if (!teamId.equals(lastTeamId)) {
                    allTeamsExceptLast.add(team);
                }
            }

            if (!allTeamsExceptLast.isEmpty()) {
                Document selectedTeam = allTeamsExceptLast.get(random.nextInt(allTeamsExceptLast.size()));

                // Trouver une équipe originale (pour afficher "Choix original")
                Document originalTeam = getRandomTeamExcept(selectedTeam.getObjectId("_id").toString());
                if (originalTeam != null) {
                    originalTeamLogo = originalTeam.getString("logo");
                }

                updateDraftHistory(selectedTeam.getObjectId("_id").toString(), usedTeams);
                return new TeamSelectionResult(selectedTeam, true, originalTeamLogo);
            }
        }

        // Code existant pour la sélection normale (non-trade)...
        if (usedTeams.size() >= totalTeams - 1) usedTeams.clear();

        List<Document> availableTeams = new ArrayList<>();
        for (Document team : teamsCollection.find()) {
            String teamId = team.getObjectId("_id").toString();
            if (teamId.equals(lastTeamId)) continue;
            if (!usedTeams.contains(teamId)) availableTeams.add(team);
        }

        if (!availableTeams.isEmpty()) {
            Document selectedTeam = availableTeams.get(random.nextInt(availableTeams.size()));
            String selectedTeamId = selectedTeam.getObjectId("_id").toString();
            usedTeams.add(selectedTeamId);
            updateDraftHistory(selectedTeamId, usedTeams);
            return new TeamSelectionResult(selectedTeam, false, "");
        }

        // Fallback...
        for (Document team : teamsCollection.find()) {
            String teamId = team.getObjectId("_id").toString();
            if (!teamId.equals(lastTeamId)) {
                usedTeams.clear();
                usedTeams.add(teamId);
                updateDraftHistory(teamId, usedTeams);
                return new TeamSelectionResult(team, false, "");
            }
        }

        return null;
    }

    private Document getRandomTeamExcept(String excludeTeamId) {
        List<Document> teams = new ArrayList<>();
        for (Document team : teamsCollection.find()) {
            String teamId = team.getObjectId("_id").toString();
            if (!teamId.equals(excludeTeamId)) {
                teams.add(team);
            }
        }

        if (!teams.isEmpty()) {
            return teams.get(random.nextInt(teams.size()));
        }
        return null;
    }

    private void initializeDraftHistory() {
        Document historyDoc = new Document()
                .append("_id", "draft_tracker")
                .append("lastTeamId", "")
                .append("usedTeams", new ArrayList<String>())
                .append("totalTeams", (int)teamsCollection.countDocuments());
        draftHistoryCollection.insertOne(historyDoc);
    }

    private void updateDraftHistory(String selectedTeamId, List<String> usedTeams) {
        draftHistoryCollection.updateOne(
                new Document("_id", "draft_tracker"),
                new Document("$set", new Document("lastTeamId", selectedTeamId)
                        .append("usedTeams", usedTeams))
        );
    }

    private byte[] generateDraftImage(String teamName, String logoUrl, String colorHex, Member member, String originalTeam) throws IOException, URISyntaxException {
        return generateDraftImage(teamName, logoUrl, colorHex, member, originalTeam, member.getGuild().getMemberCount() - 1);
    }

    private byte[] generateDraftImage(String teamName, String logoUrl, String colorHex, Member member, String originalTeam, int roundPick) throws IOException, URISyntaxException {
        int width = 800;
        int height = 400;
        String playerName = member.getEffectiveName();
        String draftPick = roundPick + "e";

        // Créer l'image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Configurer la qualité du rendu
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Convertir la couleur hex en couleur Java
        Color teamColor = parseColor(colorHex);

        // Créer un dégradé de la couleur de l'équipe (plus foncé) vers le noir
        GradientPaint gradient = new GradientPaint(0, 0, darkerColor(teamColor), width, height, Color.BLACK);
        g.setPaint(gradient);
        g.fillRect(0, 0, width, height);

        // Charger le logo de l'équipe pour le fond (watermark)
        BufferedImage logoForBackground = ImageIO.read(new URI(logoUrl).toURL());
        int bgLogoSize = 350;
        int bgLogoX = width - bgLogoSize - 50;
        int bgLogoY = (height - bgLogoSize) / 2;

        // Créer une version semi-transparente du logo
        AlphaComposite alphaChannel = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f);
        g.setComposite(alphaChannel);
        g.drawImage(logoForBackground, bgLogoX, bgLogoY, bgLogoSize, bgLogoSize, null);

        // Réinitialiser l'opacité pour le reste du dessin
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        // Charger et dessiner le logo de l'équipe principal
        BufferedImage logo = ImageIO.read(new URI(logoUrl).toURL());
        int logoSize = 150;
        g.drawImage(logo, 50, (height - logoSize) / 2, logoSize, logoSize, null);

        // Configurer les polices
        Font nameFont = FontUtils.calculateOptimalNameFont(g, playerName, 400, 60);
        Font pickFont = new Font("Arial", Font.BOLD, 40);
        Font teamFont = new Font("Arial", Font.BOLD, 30);

        // Définir la position de base pour le texte
        int textX = 230;
        int textY = height / 2 + 20;

        // Dessiner un fond semi-transparent pour le nom de l'équipe
        g.setFont(teamFont);
        FontMetrics teamMetrics = g.getFontMetrics();
        String teamNameUpper = teamName.toUpperCase();
        int teamNameWidth = teamMetrics.stringWidth(teamNameUpper);
        int teamNameHeight = teamMetrics.getHeight();

        // Rectangle semi-transparent derrière le nom de l'équipe
        Color semiTransparentBg = new Color(0, 0, 0, 150); // Noir semi-transparent
        g.setColor(semiTransparentBg);

        // Hauteur ajustée pour le rectangle du nom d'équipe
        int teamBoxHeight = teamNameHeight + 10; // +10 pour le padding
        int teamBoxY = textY - 70 - teamNameHeight;
        g.fillRoundRect(textX - 10, teamBoxY, teamNameWidth + 20, teamBoxHeight, 10, 10);

        // Dessiner le nom de l'équipe
        g.setFont(teamFont);
        g.setColor(teamColor.brighter());
        g.drawString(teamNameUpper, textX, textY - 70);

        // Ajouter l'information sur l'équipe d'origine sous le nom de l'équipe
        if (originalTeam != null && !originalTeam.isEmpty()) {
            int originalLogoSize = 30; // Légèrement plus petit pour mieux s'adapter à la hauteur de la boîte
            int teamTextEndX = textX + teamNameWidth;
            int additionalSpace = 15; // Espace supplémentaire en pixels
            int arrowX = teamTextEndX + 15 + additionalSpace;
            int arrowWidth = 40; // Largeur de la flèche dessinée
            int boxWidth = arrowWidth + 20 + originalLogoSize + additionalSpace;

            // Rectangle semi-transparent derrière la flèche et le logo
            g.setColor(semiTransparentBg);
            g.fillRoundRect(arrowX - 5, teamBoxY, boxWidth, teamBoxHeight, 10, 10);

            // Centre vertical de la boîte pour aligner les flèches et le logo
            int centerY = teamBoxY + teamBoxHeight /2;

            // Utiliser un symbole Unicode pour la flèche bidirectionnelle au lieu de la dessiner
            Font arrowFont = new Font("Arial Unicode MS", Font.BOLD, 24);
            g.setFont(arrowFont);
            g.setColor(Color.GRAY);
            FontMetrics arrowMetrics = g.getFontMetrics();
            String arrowSymbol = "⇆"; // Symbole Unicode ⇆
            int arrowSymbolWidth = arrowMetrics.stringWidth(arrowSymbol);
            // Centrer le symbole dans l'espace alloué
            int arrowSymbolX = arrowX + (arrowWidth - arrowSymbolWidth) / 2;
            // Ajuster verticalement pour centrer le symbole
            int arrowSymbolY = centerY + arrowMetrics.getAscent()/2 - 2;
            g.drawString(arrowSymbol, arrowSymbolX, arrowSymbolY);

            // Solution complète pour le logo d'échange
            try {
                // Position du logo (après la flèche)
                int originalLogoX = arrowX + arrowWidth + 5;
                int originalLogoY = teamBoxY + (teamBoxHeight - originalLogoSize) / 2;

                // Correction de la position verticale - déplacer légèrement vers le bas
                int verticalAdjustment = 1; // Déplacer de 1px vers le bas
                originalLogoY += verticalAdjustment;

                // Taille optimisée du logo
                int maxLogoSize = teamBoxHeight - 8; // Réduire légèrement pour mieux s'adapter
                int displaySize = Math.min(originalLogoSize, maxLogoSize);

                // Chargement optimisé
                URL originalLogoUrl = new URI(originalTeam).toURL();
                HttpURLConnection connection = (HttpURLConnection) originalLogoUrl.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setDoInput(true);
                connection.setConnectTimeout(5000);

                BufferedImage originalLogo = ImageIO.read(connection.getInputStream());
                BufferedImage transparentLogo = new BufferedImage(originalLogo.getWidth(), originalLogo.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < originalLogo.getHeight(); y++) {
                    for (int x = 0; x < originalLogo.getWidth(); x++) {
                        int rgb = originalLogo.getRGB(x, y);
                        Color pixelColor = new Color(rgb, true);

                        // Si le pixel est blanc ou presque blanc, le rendre transparent
                        if (pixelColor.getRed() > 240 && pixelColor.getGreen() > 240 && pixelColor.getBlue() > 240) {
                            transparentLogo.setRGB(x, y, new Color(0, 0, 0, 0).getRGB());
                        } else {
                            transparentLogo.setRGB(x, y, rgb);
                        }
                    }
                }

                // Utiliser l'image avec transparence à la place de l'originale
                originalLogo = transparentLogo;

                // Prétraitement pour optimiser la qualité
                // Utiliser une technique de suréchantillonnage (oversampling)
                int oversampleFactor = 4;
                int workSize = displaySize * oversampleFactor;

                BufferedImage workImage = new BufferedImage(workSize, workSize, BufferedImage.TYPE_INT_ARGB);
                Graphics2D workG = workImage.createGraphics();

                // Qualité maximale
                workG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                workG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                workG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                workG.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

                // Dessiner en grand pour ensuite réduire
                workG.drawImage(originalLogo, 0, 0, workSize, workSize, null);
                workG.dispose();

                // Position précise pour centrage parfait
                int exactX = originalLogoX + (originalLogoSize - displaySize) / 2;
                int exactY = originalLogoY + (originalLogoSize - displaySize) / 2;

                // Amélioration du rendu final
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Appliquer une légère accentuation pour améliorer la netteté
                float[] sharpenKernel = {
                        0, -0.2f, 0,
                        -0.2f, 1.8f, -0.2f,
                        0, -0.2f, 0
                };
                Kernel kernel = new Kernel(3, 3, sharpenKernel);
                BufferedImageOp sharpenOp = new ConvolveOp(kernel);
                BufferedImage sharpened = sharpenOp.filter(workImage, null);

                // Dessiner l'image finale
                g.drawImage(sharpened, exactX, exactY, displaySize, displaySize, null);

                // Ajouter un léger contour en option pour mieux délimiter les logos à fond transparent
                // Supprimé pour éviter d'ajouter un contour non désiré
            } catch (Exception e) {
                System.err.println("Erreur logo: " + e.getMessage());
                Font originalTeamFont = new Font("Arial", Font.ITALIC, 22);
                g.setFont(originalTeamFont);
                g.setColor(Color.GRAY);
                g.drawString("?", arrowX + arrowWidth + 10, centerY + 7);
            }
        }

        // Préparer les éléments du texte séparément
        String separatorBar = "|";

        // Mesurer d'abord les largeurs des textes
        g.setFont(nameFont);
        FontMetrics nameMetrics = g.getFontMetrics();
        int nameWidth = nameMetrics.stringWidth(playerName);

        g.setFont(pickFont);
        FontMetrics pickMetrics = g.getFontMetrics();
        int separatorWidth = pickMetrics.stringWidth(separatorBar);
        int pickWidth = pickMetrics.stringWidth(draftPick);

        // Calculer la largeur totale qui sera utilisée pour la ligne
        int separatorSpace = 20; // Espace avant et après le séparateur
        int totalWidth = nameWidth + separatorSpace + separatorWidth + separatorSpace + pickWidth;

        // Dessiner le nom du joueur
        g.setFont(nameFont);
        g.setColor(Color.WHITE);
        g.drawString(playerName, textX, textY);

        // Calculer l'ajustement pour aligner le texte avec le nom
        int pickAdjustment = -5; // Ajustez cette valeur selon le besoin
        int pickTextY = textY + pickAdjustment;

        // Position du séparateur
        int separatorX = textX + nameWidth + separatorSpace;
        int separatorAdjustment = -10;
        int separatorY = textY + separatorAdjustment;

        // Dessiner la barre en GRIS
        g.setFont(pickFont);
        g.setColor(Color.GRAY); // Couleur grise pour la barre
        g.drawString(separatorBar, separatorX, separatorY);

        // Dessiner le choix de draft
        g.setColor(Color.WHITE); // Revenir au blanc pour le numéro de choix
        g.drawString(draftPick, separatorX + separatorWidth + separatorSpace, pickTextY);

        // Dessiner la ligne EXACTEMENT de la même largeur que le texte
        g.setColor(teamColor);
        int lineY = textY + 25;
        g.fillRect(textX, lineY, totalWidth, 5);

        g.dispose();

        // Convertir l'image en bytes pour l'envoi
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private Color darkerColor(Color color) {
        return new Color(
                Math.max((int)(color.getRed() * (float) 0.8), 0),
                Math.max((int)(color.getGreen() * (float) 0.8), 0),
                Math.max((int)(color.getBlue() * (float) 0.8), 0)
        );
    }

    private Color parseColor(String colorCode) {
        if (colorCode == null || colorCode.isEmpty()) return Color.BLUE;
        try {
            if (colorCode.startsWith("#")) {
                return Color.decode(colorCode);
            } else if (colorCode.startsWith("0x")) {
                return Color.decode(colorCode);
            } else {
                return Color.decode("#" + colorCode);
            }
        } catch (NumberFormatException e) {
            System.err.println("Code couleur invalide: " + colorCode);
            return Color.BLUE;
        }
    }
}
