package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.reputation.ReputationManager;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import lombok.Setter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
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
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ComLeaderboard extends Command {

    private final String channelId;
    private static final int DISPLAY_COUNT = 10;

    public ComLeaderboard() {
        super("leaderboard", "Affiche le classement des joueurs par réputation");
        setDefaultPermissions(DefaultMemberPermissions.ENABLED);
        addOption(OptionType.USER, "membre", "Utilisateur à rechercher dans le classement", false);

        Dotenv dotenv = Dotenv.load();
        this.channelId = dotenv.get("CHANNEL_QG_DES_STATS_ID");
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (channelId == null || !Objects.requireNonNull(e.getChannelId()).equalsIgnoreCase(channelId)) {
            e.reply("> 🛑 Cette commande ne peut être utilisée ici.\n" +
                    "> Dirige-toi vers le salon <#" + channelId + "> pour utiliser /leaderboard.").setEphemeral(true).queue();
            return;
        }

        e.deferReply().queue();

        Member searchedMember = e.getOption("membre") != null ? Objects.requireNonNull(e.getOption("membre")).getAsMember() : null;
        String searchedUserId = searchedMember != null ? searchedMember.getId() : null;
        try {
            List<Document> allUsers = Main.getRankManager().getAllUsersData();
            List<UserReputation> allUsersReputation = new ArrayList<>();

            Guild guild = e.getGuild();
            if (guild == null) {
                e.getHook().sendMessage("Erreur: Impossible de récupérer les informations du serveur.").queue();
                return;
            }

            for (Document userData : allUsers) {
                String userId = userData.getString("userId");
                Document reputation = (Document) userData.get("reputation");
                if (reputation == null) reputation = new Document();
                int reputationScore = reputation.getInteger("reputationScore");
                String reputationRank = ReputationManager.getReputationRank(reputationScore);
                String teamName = userData.getString("teamName");

                // Récupérer les informations de l'équipe depuis la DB teams
                Document teamData = Main.getTeamManager().getTeamByName(teamName);
                String logoPath = teamData != null ? logoPath = teamData.getString("logo") : null;

                try {
                    Member member = guild.retrieveMemberById(userId).complete();
                    if (member != null) {
                        String username = member.getEffectiveName();
                        allUsersReputation.add(new UserReputation(userId, username, reputationScore, reputationRank, teamName, logoPath));
                    }
                } catch (Exception ignored) {}
            }

            // Trier par score de réputation (décroissant)
            allUsersReputation.sort(Comparator.comparingInt(UserReputation::getReputationScore).reversed());

            // Assigner des rangs
            for (int i = 0; i < allUsersReputation.size(); i++) {
                allUsersReputation.get(i).setRank(i + 1);
            }

            // Déterminer les joueurs à afficher
            List<UserReputation> usersToDisplay;
            if (searchedUserId == null) {
                usersToDisplay = allUsersReputation.stream().limit(DISPLAY_COUNT).collect(Collectors.toList());
            } else {
                Optional<UserReputation> foundUser = allUsersReputation.stream().filter(user -> user.getUserId().equals(searchedUserId)).findFirst();
                if (foundUser.isPresent()) {
                    UserReputation user = foundUser.get();
                    int userRank = allUsersReputation.indexOf(user);

                    // Calculer la plage à afficher (centrée sur le joueur recherché)
                    int startIndex = Math.max(0, userRank - DISPLAY_COUNT / 2);
                    int endIndex = Math.min(allUsersReputation.size(), startIndex + DISPLAY_COUNT);

                    // Ajuster startIndex si endIndex est limité par la taille de la liste
                    if (endIndex == allUsersReputation.size() && endIndex - startIndex < DISPLAY_COUNT) {
                        startIndex = Math.max(0, endIndex - DISPLAY_COUNT);
                    }

                    usersToDisplay = allUsersReputation.subList(startIndex, endIndex);
                    e.getHook().sendMessage("✅ **" + user.getUsername() + "** est classé #" + user.getRank() + " !").queue();
                } else {
                    usersToDisplay = allUsersReputation.stream().limit(DISPLAY_COUNT).collect(Collectors.toList());
                    e.getHook().sendMessage("❌ Le membre " + searchedUserId + " n'a pas été trouvé. Voici le top " + DISPLAY_COUNT + ".").queue();
                }
            }

            // Générer l'image du leaderboard
            ByteArrayOutputStream outputStream = generateLeaderboardImage(usersToDisplay);
            e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), "leaderboard.png")).queue();

        } catch (Exception ex) {
            e.getHook().sendMessage("Erreur lors de la génération du classement: " + ex.getMessage()).queue();
            ex.printStackTrace();
        }
    }

    private ByteArrayOutputStream generateLeaderboardImage(List<UserReputation> users) throws IOException {
        final int width = 800;

        // Hauteur calculée précisément pour le nombre d'utilisateurs
        int height = 130 + users.size() * 60 + 20; // 130px d'en-tête + 60px par utilisateur + 20px de padding

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Assurer que l'image couvre toute la largeur
        g2d.setClip(0, 0, width, height);

        // Configurer l'antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Fond principal - dégradé de bleu foncé à noir
        GradientPaint backgroundGradient = new GradientPaint(0, 0, new Color(25, 40, 65), 0, height, new Color(10, 15, 30));
        g2d.setPaint(backgroundGradient);
        g2d.fillRect(0, 0, width, height);

        // Titre du leaderboard
        g2d.setFont(new Font("Arial Unicode MS", Font.BOLD, 36));
        g2d.setColor(Color.WHITE);
        String title = "CLASSEMENT";
        FontMetrics metrics = g2d.getFontMetrics();
        int titleX = (width - metrics.stringWidth(title)) / 2;
        g2d.drawString(title, titleX, 60);

        // Ligne de séparation sous le titre
        g2d.setColor(new Color(200, 200, 200, 120));
        g2d.fillRect(50, 75, width - 100, 2);

        // Entêtes des colonnes
        g2d.setFont(new Font("Arial Unicode MS", Font.BOLD, 18));
        g2d.setColor(new Color(180, 180, 180));
        g2d.drawString("RANG", 50, 110);
        g2d.drawString("JOUEUR", 150, 110);
        g2d.drawString("ÉQUIPE", 336, 110);
        g2d.drawString("NIVEAU", 500, 110);
        g2d.drawString("SCORE", 683, 110);

        // Ligne séparatrice sous les entêtes
        g2d.setColor(new Color(100, 100, 100, 80));
        g2d.fillRect(50, 120, width - 100, 1);

        // Dessiner chaque ligne du classement
        int rowY = 130;

        for (UserReputation user : users) {
            // Couleur de fond alternée pour les lignes
            if (user.getRank() % 2 == 0) {
                g2d.setColor(new Color(40, 60, 85, 80));
            } else {
                g2d.setColor(new Color(50, 70, 95, 60));
            }
            g2d.fillRect(40, rowY, width - 80, 50);

            // Position (rang)
            g2d.setFont(new Font("Arial Unicode MS", Font.BOLD, 24));

            // Couleurs spéciales pour le top 3
            switch (user.getRank()) {
                case 1:
                    g2d.setColor(new Color(255, 215, 0)); // Or
                    break;
                case 2:
                    g2d.setColor(new Color(192, 192, 192)); // Argent
                    break;
                case 3:
                    g2d.setColor(new Color(205, 127, 50)); // Bronze
                    break;
                default:
                    g2d.setColor(Color.WHITE);
                    break;
            }

            g2d.drawString("#" + user.getRank(), 60, rowY + 32);

            // Nom d'utilisateur
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial Unicode MS", Font.BOLD, 20));
            g2d.drawString(user.getUsername(), 150, rowY + 32);

            // Équipe
            try {
                if (user.getLogoPath() != null && !user.getLogoPath().isEmpty()) {
                    BufferedImage logo = loadImage(user.getLogoPath());
                    int logoSize = 40;
                    int logoX = 350;
                    int logoY = rowY + 5;

                    // Créer un logo circulaire
                    BufferedImage circularLogo = new BufferedImage(logoSize, logoSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D logoG2d = circularLogo.createGraphics();
                    logoG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    logoG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    logoG2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    Ellipse2D.Float circle = new Ellipse2D.Float(0, 0, logoSize, logoSize);
                    logoG2d.setClip(circle);
                    logoG2d.drawImage(logo, 0, 0, logoSize, logoSize, null);
                    logoG2d.dispose();

                    g2d.drawImage(circularLogo, logoX, logoY, null);
                } else {
                    // Si pas de logo, afficher le nom de l'équipe comme avant
                    g2d.setFont(new Font("Arial Unicode MS", Font.PLAIN, 18));
                    g2d.setColor(new Color(180, 180, 180));
                    g2d.drawString(user.getTeamName(), 400, rowY + 32);
                }
            } catch (Exception e) {
                // En cas d'erreur, afficher le nom de l'équipe
                g2d.setFont(new Font("Arial Unicode MS", Font.PLAIN, 18));
                g2d.setColor(new Color(180, 180, 180));
                g2d.drawString(user.getTeamName(), 400, rowY + 32);
                System.err.println("Erreur lors du chargement du logo: " + e.getMessage());
            }

            // Niveau de réputation
            g2d.setColor(ReputationManager.getRepurationRankColor(user.getReputationRank()));
            g2d.drawString(user.getReputationRank(), 500, rowY + 32);

            // Score
            g2d.setColor(Color.WHITE);
            g2d.drawString(String.valueOf(user.getReputationScore()), 710, rowY + 32);

            rowY += 60;
        }

        g2d.dispose();

        // Convertir l'image en ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream;
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

    // Classe pour stocker les informations de réputation d'un utilisateur
    @Getter
    @Setter
    private static class UserReputation {

        private final String userId;
        private final String username;
        private final int reputationScore;
        private final String reputationRank;
        private final String teamName;
        private final String logoPath;
        private int rank;

        public UserReputation(String userId, String username, int reputationScore, String reputationRank, String teamName, String logoPath) {
            this.userId = userId;
            this.username = username;
            this.reputationScore = reputationScore;
            this.reputationRank = reputationRank;
            this.teamName = teamName;
            this.logoPath = logoPath;
            this.rank = 0;
        }
    }
}