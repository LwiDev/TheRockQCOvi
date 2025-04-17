package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.reputation.ReputationManager;
import ca.lwi.trqcbot.utils.FontUtils;
import ca.lwi.trqcbot.utils.ImageUtils;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import lombok.Setter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ComLeaderboard extends Command {

    private final String channelId;
    private static final int DISPLAY_COUNT = 10;

    public ComLeaderboard() {
        super("leaderboard", "Affiche le classement des joueurs/équipes");
        setDefaultPermissions(DefaultMemberPermissions.ENABLED);

        // Sous-commande pour les joueurs
        SubcommandData playersSubcommand = new SubcommandData("players", "Affiche le classement des joueurs");
        playersSubcommand.addOption(OptionType.USER, "membre", "Utilisateur à rechercher dans le classement", false);

        // Sous-commande pour les équipes
        SubcommandData teamsSubcommand = new SubcommandData("teams", "Affiche le classement des équipes");
        teamsSubcommand.addOption(OptionType.STRING, "equipe", "Équipe à rechercher dans le classement", false, true);

        // Ajouter les sous-commandes
        addSubcommands(playersSubcommand, teamsSubcommand);

        Dotenv dotenv = Dotenv.load();
        this.channelId = dotenv.get("CHANNEL_QG_DES_STATS_ID");
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) {
        if (e.getName().equals("leaderboard")) {
            if (Objects.equals(e.getSubcommandName(), "equipes") && e.getFocusedOption().getName().equals("equipe")) {
                List<Document> allTeams = Main.getTeamManager().getAllTeams();
                String searchTerm = e.getFocusedOption().getValue().toLowerCase();
                List<String> teamNames = allTeams.stream()
                        .map(team -> team.getString("name"))
                        .filter(name -> name.toLowerCase().contains(searchTerm))
                        .limit(25)
                        .collect(Collectors.toList());
                e.replyChoiceStrings(teamNames).queue();
            }
        }
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (channelId == null || !Objects.requireNonNull(e.getChannelId()).equalsIgnoreCase(channelId)) {
            e.reply("> 🛑 Cette commande ne peut être utilisée ici.\n" +
                    "> Dirige-toi vers le salon <#" + channelId + "> pour utiliser /leaderboard.").setEphemeral(true).queue();
            return;
        }

        e.deferReply().queue();

        String subcommandName = e.getSubcommandName();

        if (subcommandName == null) {
            e.getHook().sendMessage("Une erreur s'est produite. Veuillez réessayer.").queue();
            return;
        }

        switch (subcommandName) {
            case "joueurs":
                Member searchedMember = e.getOption("membre") != null ? Objects.requireNonNull(e.getOption("membre")).getAsMember() : null;
                String searchedUserId = searchedMember != null ? searchedMember.getId() : null;
                generatePlayerLeaderboard(e, searchedUserId);
                break;
            case "equipes":
                String searchedTeamName = e.getOption("equipe") != null ? Objects.requireNonNull(e.getOption("equipe")).getAsString() : null;
                generateTeamLeaderboard(e, searchedTeamName);
                break;
            default:
                e.getHook().sendMessage("Commande non reconnue. Veuillez utiliser /leaderboard joueurs ou /leaderboard equipes").queue();
        }
    }

    private void generatePlayerLeaderboard(SlashCommandInteractionEvent e, String searchedUserId) {
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
                String logoPath = teamData != null ? teamData.getString("logo") : null;

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
            ByteArrayOutputStream outputStream = generatePlayerLeaderboardImage(usersToDisplay);
            e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), "leaderboard.png")).queue();

        } catch (Exception ex) {
            e.getHook().sendMessage("Erreur lors de la génération du classement: " + ex.getMessage()).queue();
            ex.printStackTrace();
        }
    }

    private void generateTeamLeaderboard(SlashCommandInteractionEvent e, String searchedTeamName) {
        try {
            if (searchedTeamName != null) {
                displayTeamMembers(e, searchedTeamName);
                return;
            }

            // Récupérer toutes les équipes
            List<Document> allTeams = Main.getTeamManager().getAllTeams();
            List<TeamReputation> allTeamsReputation = new ArrayList<>();

            // Pour chaque équipe
            for (Document teamData : allTeams) {
                String teamName = teamData.getString("name");
                String logoPath = teamData.getString("logo");

                TeamReputation teamRep = new TeamReputation(teamName, logoPath);

                // Récupérer tous les membres de cette équipe
                List<Document> allUsers = Main.getRankManager().getAllUsersData();
                for (Document userData : allUsers) {
                    String userTeam = userData.getString("teamName");
                    if (userTeam != null && userTeam.equals(teamName)) {
                        Document reputation = (Document) userData.get("reputation");
                        if (reputation != null) {
                            int reputationScore = reputation.getInteger("reputationScore", 0);
                            teamRep.addMemberScore(reputationScore);
                        }
                    }
                }

                allTeamsReputation.add(teamRep);
            }

            // Trier par score de réputation d'équipe (décroissant)
            allTeamsReputation.sort(Comparator.comparingInt(TeamReputation::getAverageReputationScore).reversed());

            // Assigner des rangs (positions dans le classement)
            for (int i = 0; i < allTeamsReputation.size(); i++) {
                allTeamsReputation.get(i).setRank(i + 1);
            }

            // Déterminer les équipes à afficher
            List<TeamReputation> teamsToDisplay;
            teamsToDisplay = allTeamsReputation.stream().limit(DISPLAY_COUNT).collect(Collectors.toList());

            // Générer l'image du leaderboard
            ByteArrayOutputStream outputStream = generateTeamLeaderboardImage(teamsToDisplay);
            e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), "team_leaderboard.png")).queue();

        } catch (Exception ex) {
            e.getHook().sendMessage("Erreur lors de la génération du classement des équipes: " + ex.getMessage()).queue();
            ex.printStackTrace();
        }
    }

    private void displayTeamMembers(SlashCommandInteractionEvent e, String teamName) {
        try {
            // Vérifier si l'équipe existe
            Document teamData = Main.getTeamManager().getTeamByName(teamName);
            if (teamData == null) {
                e.getHook().sendMessage("❌ L'équipe \"" + teamName + "\" n'a pas été trouvée.").queue();
                return;
            }

            // Récupérer tous les membres de cette équipe
            List<Document> allUsers = Main.getRankManager().getAllUsersData();
            List<UserReputation> teamMembers = new ArrayList<>();

            Guild guild = e.getGuild();
            if (guild == null) {
                e.getHook().sendMessage("Erreur: Impossible de récupérer les informations du serveur.").queue();
                return;
            }

            String logoPath = teamData.getString("logo");

            for (Document userData : allUsers) {
                String userTeam = userData.getString("teamName");
                if (userTeam != null && userTeam.equals(teamName)) {
                    String userId = userData.getString("userId");
                    Document reputation = (Document) userData.get("reputation");
                    if (reputation == null) reputation = new Document();
                    int reputationScore = reputation.getInteger("reputationScore", 0);
                    String reputationRank = ReputationManager.getReputationRank(reputationScore);
                    try {
                        Member member = guild.retrieveMemberById(userId).complete();
                        if (member != null) {
                            String username = member.getEffectiveName();
                            teamMembers.add(new UserReputation(userId, username, reputationScore, reputationRank, teamName, logoPath));
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Trier par score de réputation (décroissant)
            teamMembers.sort(Comparator.comparingInt(UserReputation::getReputationScore).reversed());

            // Assigner des rangs
            for (int i = 0; i < teamMembers.size(); i++) {
                teamMembers.get(i).setRank(i + 1);
            }

            if (teamMembers.isEmpty()) {
                e.getHook().sendMessage("L'équipe **" + teamName + "** n'a aucun membre.").queue();
                return;
            }

            e.getHook().sendMessage("✅ Classement de l'équipe **" + teamName + "** (" + teamMembers.size() + " joueur" + (teamMembers.size() > 1 ? "s" : "") + ")").queue();

            // Générer l'image du leaderboard des membres
            ByteArrayOutputStream outputStream = generatePlayerLeaderboardImage(teamMembers);
            e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), "team_members.png")).queue();

        } catch (Exception ex) {
            e.getHook().sendMessage("Erreur lors de l'affichage des membres de l'équipe: " + ex.getMessage()).queue();
            ex.printStackTrace();
        }
    }

    private ByteArrayOutputStream generatePlayerLeaderboardImage(List<UserReputation> users) throws IOException, FontFormatException {
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
        g2d.setFont(new Font("Arial", Font.BOLD, 36));
        g2d.setColor(Color.WHITE);
        String title = "CLASSEMENT";
        FontMetrics metrics = g2d.getFontMetrics();
        int titleX = (width - metrics.stringWidth(title)) / 2;
        g2d.drawString(title, titleX, 60);

        // Ligne de séparation sous le titre
        g2d.setColor(new Color(200, 200, 200, 120));
        g2d.fillRect(50, 75, width - 100, 2);

        // Entêtes des colonnes
        g2d.setFont(new Font("Arial", Font.BOLD, 18));
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
            g2d.setFont(new Font("Arial", Font.BOLD, 24));

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
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            g2d.drawString(user.getUsername(), 150, rowY + 32);

            // Équipe
            try {
                if (user.getLogoPath() != null && !user.getLogoPath().isEmpty()) {
                    // Charger l'image (SVG ou autre)
                    BufferedImage logo = ImageUtils.loadImage(user.getLogoPath(), 80);

                    // Définir la taille souhaitée pour le logo
                    int logoHeight = 40;
                    // Calculer la largeur en préservant le ratio d'aspect
                    int logoWidth = (int) (logoHeight * ((double) logo.getWidth() / logo.getHeight()));

                    // Position du logo
                    int logoX = 350;
                    int logoY = rowY + 5;

                    // Redimensionner avec haute qualité
                    BufferedImage resizedLogo = new BufferedImage(logoWidth, logoHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D logoG2d = resizedLogo.createGraphics();

                    // Paramètres de rendu pour une qualité optimale
                    logoG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    logoG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    logoG2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    logoG2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

                    // Dessiner l'image
                    logoG2d.drawImage(logo, 0, 0, logoWidth, logoHeight, null);
                    logoG2d.dispose();

                    // Afficher le logo sur l'image principale du leaderboard
                    g2d.drawImage(resizedLogo, logoX, logoY, null);
                } else {
                    // Si pas de logo, afficher le nom de l'équipe
                    g2d.setFont(new Font("Arial", Font.BOLD, 18));
                    g2d.setColor(new Color(180, 180, 180));
                    g2d.drawString(user.getTeamName(), 350, rowY + 32);
                }
            } catch (Exception e) {
                // En cas d'erreur, afficher le nom de l'équipe
                g2d.setFont(new Font("Arial", Font.BOLD, 18));
                g2d.setColor(new Color(180, 180, 180));
                g2d.drawString(user.getTeamName(), 350, rowY + 32);
                System.err.println("Erreur lors du chargement du logo: " + e.getMessage());
                e.printStackTrace(); // Pour un débogage plus complet
            }

            // Niveau de réputation
            FontUtils.drawMixedEmojiText(g2d, user.getReputationRank(), 500, rowY + 32, 18, new Font("Arial", Font.BOLD, 18), ReputationManager.getReputationRankColor(user.getReputationRank()));

            // Score
            g2d.setColor(Color.WHITE);
            g2d.drawString(user.getReputationScore() + "%", 710, rowY + 32);

            rowY += 60;
        }

        g2d.dispose();

        // Convertir l'image en ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream;
    }

    private ByteArrayOutputStream generateTeamLeaderboardImage(List<TeamReputation> teams) throws IOException, FontFormatException {
        final int width = 800;

        // Hauteur calculée précisément pour le nombre d'équipes
        int height = 130 + teams.size() * 60 + 20; // 130px d'en-tête + 60px par équipe + 20px de padding

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
        g2d.setFont(new Font("Arial", Font.BOLD, 36));
        g2d.setColor(Color.WHITE);
        String title = "CLASSEMENT DES ÉQUIPES";
        FontMetrics metrics = g2d.getFontMetrics();
        int titleX = (width - metrics.stringWidth(title)) / 2;
        g2d.drawString(title, titleX, 60);

        // Ligne de séparation sous le titre
        g2d.setColor(new Color(200, 200, 200, 120));
        g2d.fillRect(50, 75, width - 100, 2);

        // Entêtes des colonnes
        g2d.setFont(new Font("Arial", Font.BOLD, 18));
        g2d.setColor(new Color(180, 180, 180));
        g2d.drawString("RANG", 50, 110);
        g2d.drawString("ÉQUIPE", 150, 110);
        g2d.drawString("MEMBRES", 500, 110);
        g2d.drawString("SCORE", 683, 110);

        // Ligne séparatrice sous les entêtes
        g2d.setColor(new Color(100, 100, 100, 80));
        g2d.fillRect(50, 120, width - 100, 1);

        // Dessiner chaque ligne du classement
        int rowY = 130;

        for (TeamReputation team : teams) {
            // Couleur de fond alternée pour les lignes
            if (team.getRank() % 2 == 0) {
                g2d.setColor(new Color(40, 60, 85, 80));
            } else {
                g2d.setColor(new Color(50, 70, 95, 60));
            }
            g2d.fillRect(40, rowY, width - 80, 50);

            // Position (rang)
            g2d.setFont(new Font("Arial", Font.BOLD, 24));

            // Couleurs spéciales pour le top 3
            switch (team.getRank()) {
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

            g2d.drawString("#" + team.getRank(), 60, rowY + 32);

            // Nom d'équipe et logo
            try {
                if (team.getLogoPath() != null && !team.getLogoPath().isEmpty()) {
                    // Charger l'image
                    BufferedImage logo = ImageUtils.loadImage(team.getLogoPath(), 80);

                    // Définir la taille souhaitée pour le logo
                    int logoHeight = 40;
                    // Calculer la largeur en préservant le ratio d'aspect
                    int logoWidth = (int) (logoHeight * ((double) logo.getWidth() / logo.getHeight()));

                    // Position du logo
                    int logoX = 150;
                    int logoY = rowY + 5;

                    // Redimensionner avec haute qualité
                    BufferedImage resizedLogo = new BufferedImage(logoWidth, logoHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D logoG2d = resizedLogo.createGraphics();

                    // Paramètres de rendu pour une qualité optimale
                    logoG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    logoG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    logoG2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    logoG2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

                    // Dessiner l'image
                    logoG2d.drawImage(logo, 0, 0, logoWidth, logoHeight, null);
                    logoG2d.dispose();

                    // Afficher le logo sur l'image principale du leaderboard
                    g2d.drawImage(resizedLogo, logoX, logoY, null);

                    // Afficher le nom à côté du logo
                    g2d.setFont(new Font("Arial", Font.BOLD, 20));
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(team.getTeamName(), logoX + logoWidth + 10, rowY + 32);
                } else {
                    // Si pas de logo, afficher le nom de l'équipe
                    g2d.setFont(new Font("Arial", Font.BOLD, 20));
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(team.getTeamName(), 150, rowY + 32);
                }
            } catch (Exception e) {
                // En cas d'erreur, afficher le nom de l'équipe
                g2d.setFont(new Font("Arial", Font.BOLD, 20));
                g2d.setColor(Color.WHITE);
                g2d.drawString(team.getTeamName(), 150, rowY + 32);
                System.err.println("Erreur lors du chargement du logo: " + e.getMessage());
                e.printStackTrace();
            }

            // Nombre de membres
            g2d.setFont(new Font("Arial", Font.BOLD, 18));
            g2d.setColor(new Color(180, 180, 180));
            g2d.drawString(String.valueOf(team.getMemberCount()), 520, rowY + 32);

            // Score moyen
            g2d.setColor(Color.WHITE);
            g2d.drawString(team.getAverageReputationScore() + "%", 710, rowY + 32);

            rowY += 60;
        }

        g2d.dispose();

        // Convertir l'image en ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream;
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

    @Getter
    @Setter
    private static class TeamReputation {

        private final String teamName;
        private int memberCount;
        private int totalReputationScore;
        private final String logoPath;
        private int rank;

        public TeamReputation(String teamName, String logoPath) {
            this.teamName = teamName;
            this.memberCount = 0;
            this.totalReputationScore = 0;
            this.logoPath = logoPath;
            this.rank = 0;
        }

        public void addMemberScore(int score) {
            this.memberCount++;
            this.totalReputationScore += score;
        }

        public int getAverageReputationScore() {
            return this.memberCount > 0 ? this.totalReputationScore / this.memberCount : 0;
        }
    }
}