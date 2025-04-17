package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import ca.lwi.trqcbot.utils.ImageUtils;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command.Choice;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ComTeam extends Command {

    private final MongoCollection<Document> usersCollection;
    private final MongoCollection<Document> teamsCollection;
    private final MongoCollection<Document> lineupCollection;
    private final MongoCollection<Document> retiredNumbersCollection;

    private final String veteranRoleId;
    private final String guildId;

    private static final int FORWARDS_COUNT = 12;
    private static final int DEFENDERS_COUNT = 6;
    private static final int GOALIES_COUNT = 2;

    private static final List<Integer> GOALIE_NUMBERS = Arrays.asList(1, 29, 30, 31, 33, 35, 40, 41, 72);
    private static final List<Integer> LEAGUE_RETIRED_NUMBERS = Collections.singletonList(99);

    private static final Logger LOGGER = LoggerFactory.getLogger(ComTeam.class);

    public ComTeam() {
        super("team", "Affiche l'alignement d'une équipe");
        MongoDatabase database = Main.getMongoConnection().getDatabase();
        this.usersCollection = database.getCollection("users");
        this.teamsCollection = database.getCollection("teams");
        this.lineupCollection = database.getCollection("teams_lineup");
        this.retiredNumbersCollection = database.getCollection("retired_numbers");
        Dotenv dotenv = Dotenv.load();
        this.veteranRoleId = dotenv.get("VETERAN_ROLE_ID");
        this.guildId = dotenv.get("GUILD_ID");
        addOption(OptionType.STRING, "team", "Nom de l'équipe", false, true);
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        Member member = e.getMember();
        if (member == null) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        e.deferReply().queue();

        String teamName;
        OptionMapping teamOption = e.getOption("team");
        if (teamOption != null) {
            teamName = teamOption.getAsString();
        } else {
            Document userDoc = usersCollection.find(Filters.eq("userId", member.getId())).first();
            if (userDoc == null || !userDoc.containsKey("teamName")) {
                e.reply("❌ Vous n'êtes pas associé à une équipe.").setEphemeral(true).queue();
                return;
            }
            teamName = userDoc.getString("teamName");
        }

        Document teamDoc = teamsCollection.find(Filters.eq("name", teamName)).first();
        if (teamDoc == null) {
            e.getHook().sendMessage("Cette équipe n'existe pas.").setEphemeral(true).queue();
            return;
        }

        try {
            Document lineupDoc = getOrCreateLineup(teamName, Objects.requireNonNull(e.getGuild()).getMembers());
            byte[] imageBytes = generateLineupImage(teamDoc, lineupDoc);
            e.getHook().sendFiles(FileUpload.fromData(imageBytes, teamName + "_lineup.png")).queue();
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", "team", ex.getMessage());
            e.getHook().sendMessage("Une erreur est survenue lors de la génération de l'alignement.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) {
        if (e.getFocusedOption().getName().equals("equipe")) {
            String query = e.getFocusedOption().getValue().toLowerCase();
            List<Choice> choices = teamsCollection.find()
                    .projection(new Document("name", 1))
                    .limit(25)
                    .map(doc -> new Choice(doc.getString("name"), doc.getString("name")))
                    .into(new ArrayList<>())
                    .stream()
                    .filter(choice -> choice.getName().toLowerCase().contains(query))
                    .limit(25)
                    .collect(Collectors.toList());
            e.replyChoices(choices).queue();
        }
    }

    private Document getOrCreateLineup(String teamName, List<Member> guildMembers) {
        Document existingLineup = lineupCollection.find(Filters.eq("team", teamName)).first();
        if (existingLineup != null) return existingLineup;

        List<Member> teamMembers = new ArrayList<>();
        guildMembers.stream().filter(member -> isMemberInTeam(member, teamName)).forEach(teamMembers::add);

        // Mélanger la liste des membres pour un choix aléatoire
        Collections.shuffle(teamMembers);

        // Créer des listes vides pour chaque position
        List<Member> forwards = new ArrayList<>();
        List<Member> defenders = new ArrayList<>();
        List<Member> goalies = new ArrayList<>();

        // Pour chaque joueur, décider aléatoirement sa position
        Random random = new Random();
        for (Member member : teamMembers) {
            // Créer une liste des positions possibles (respectant les limites)
            List<Integer> possiblePositions = new ArrayList<>();
            if (forwards.size() < FORWARDS_COUNT) possiblePositions.add(0);  // 0 = forward
            if (defenders.size() < DEFENDERS_COUNT) possiblePositions.add(1); // 1 = defender
            if (goalies.size() < GOALIES_COUNT) possiblePositions.add(2);    // 2 = goalie

            // Si toutes les positions sont remplies, on arrête
            if (possiblePositions.isEmpty()) break;

            // Choisir aléatoirement une position parmi celles disponibles
            int position = possiblePositions.get(random.nextInt(possiblePositions.size()));

            // Assigner le joueur à la position choisie
            switch (position) {
                case 0: forwards.add(member); break;
                case 1: defenders.add(member); break;
                case 2: goalies.add(member); break;
            }
        }

        Document lineupDoc = new Document("team", teamName)
                .append("forwards", createPlayersList(forwards, forwards.size(), FORWARDS_COUNT, false))
                .append("defenders", createPlayersList(defenders, defenders.size(), DEFENDERS_COUNT, false))
                .append("goalies", createPlayersList(goalies, goalies.size(), GOALIES_COUNT, true));

        lineupCollection.insertOne(lineupDoc);
        return lineupDoc;
    }

    public boolean isMemberInTeam(Member member, String teamName) {
        Document userDoc = usersCollection.find(Filters.eq("userId", member.getId())).first();
        return userDoc != null && teamName.equals(userDoc.getString("teamName"));
    }

    private List<Document> createPlayersList(List<Member> players, int endIndex, int totalNeeded, boolean isGoalie) {
        List<Document> playerDocs = new ArrayList<>();
        List<Integer> usedNumbers = getUsedNumbers();

        // Ajouter les joueurs réels disponibles
        for (int i = 0; i < endIndex; i++) {
            Member player = players.get(i);
            int number = assignNumber(player, usedNumbers, isGoalie);
            usedNumbers.add(number);
            playerDocs.add(new Document("id", player.getId())
                    .append("name", player.getEffectiveName())
                    .append("number", number));
        }

        // Ajouter des joueurs placeholders pour les positions manquantes
        int placeholdersNeeded = totalNeeded - endIndex;
        for (int i = 0; i < placeholdersNeeded; i++) {
            playerDocs.add(new Document("id", "placeholder-" + UUID.randomUUID().toString())
                    .append("name", "?")
                    .append("number", 0)
                    .append("isPlaceholder", true));
        }

        return playerDocs;
    }

    private List<Integer> getUsedNumbers() {
        List<Integer> usedNumbers = new ArrayList<>(LEAGUE_RETIRED_NUMBERS);
        List<Document> documents = lineupCollection.find().into(new ArrayList<>());
        for (Document doc : documents) {
            addNumbersFromList(usedNumbers, doc.getList("forwards", Document.class));
            addNumbersFromList(usedNumbers, doc.getList("defenders", Document.class));
            addNumbersFromList(usedNumbers, doc.getList("goalies", Document.class));
        }
        return usedNumbers;
    }

    private void addNumbersFromList(List<Integer> numbers, List<Document> players) {
        if (players != null) {
            for (Document player : players) {
                if (player.containsKey("number")) {
                    numbers.add(player.getInteger("number"));
                }
            }
        }
    }

    private int assignNumber(Member player, List<Integer> usedNumbers, boolean isGoalie) {
        if (isGoalie) {
            Collections.shuffle(GOALIE_NUMBERS);
            for (int number : GOALIE_NUMBERS) {
                if (!usedNumbers.contains(number) && !isNumberRetired(number, player.getGuild().getName())) {
                    return number;
                }
            }
        }
        Random random = new Random();
        int number;
        do {
            number = random.nextInt(98) + 1;
        } while (usedNumbers.contains(number) || isNumberRetired(number, player.getGuild().getName()));
        return number;
    }

    public boolean isVeteran(Member member) {
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(this.veteranRoleId));
    }

    private boolean isNumberRetired(int number, String teamName) {
        // Vérifier si le numéro est retiré pour cette équipe
        Document retiredDoc = retiredNumbersCollection.find(Filters.eq("team", teamName)).first();
        if (retiredDoc != null) {
            List<Document> retiredPlayers = (List<Document>) retiredDoc.get("players");
            if (retiredPlayers != null) {
                for (Document player : retiredPlayers) {
                    if (player.getInteger("number") == number) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Méthode pour permettre à un vétéran de choisir son numéro
    public void setVeteranNumber(Member player, int requestedNumber, String teamName, SlashCommandInteractionEvent e) {
        Document lineupDoc = lineupCollection.find(Filters.eq("team", teamName)).first();
        if (lineupDoc == null) {
            return;
        }

        boolean isUsedByVeteran = false;
        String currentOwner = null;

        // Vérifier si le numéro est retiré
        if (isNumberRetired(requestedNumber, teamName) || LEAGUE_RETIRED_NUMBERS.contains(requestedNumber)) {
            Document retiredDoc = retiredNumbersCollection.find(Filters.eq("team", teamName)).first();
            if (retiredDoc != null) {
                List<Document> retiredPlayers = retiredDoc.getList("players", Document.class);
                for (Document retired : retiredPlayers) {
                    if (retired.getInteger("number") == requestedNumber) {
                        currentOwner = retired.getString("player");
                        break;
                    }
                }
            }
            e.getHook().sendMessage("❌ Le numéro " + requestedNumber + " a été retiré en l'honneur de " + (currentOwner != null ? currentOwner : "un ancien joueur") + ".").setEphemeral(true).queue();
            return;
        }

        // Vérifier dans chaque catégorie de joueurs
        List<String> categories = Arrays.asList("forwards", "defenders", "goalies");
        for (String category : categories) {
            List<Document> players = lineupDoc.getList(category, Document.class);
            if (players != null) {
                for (Document p : players) {
                    if (p.getInteger("number") == requestedNumber) {
                        if (p.getBoolean("isVeteran", false)) {
                            isUsedByVeteran = true;
                            currentOwner = p.getString("name");
                        } else {
                            int newNumber = getNewNumberForPlayer();
                            updatePlayerNumber(lineupDoc, p.getString("id"), newNumber); // other player
                            updatePlayerNumber(lineupDoc, player.getId(), requestedNumber);
                            e.getHook().sendMessage("✅ Votre numéro a été changé pour le **#" + requestedNumber + "**.").setEphemeral(true).queue();
                            return;
                        }
                    }
                }
            }
        }
        if (isUsedByVeteran) {
            e.getHook().sendMessage( "❌ Le numéro **#" + requestedNumber + "** est déjà utilisé par le Vétéran **" + currentOwner + "**.").setEphemeral(true).queue();
        } else {
            updatePlayerNumber(lineupDoc, player.getId(), requestedNumber);
            e.getHook().sendMessage("✅ Votre numéro a été changé pour le **#" + requestedNumber + "**.").setEphemeral(true).queue();
        }
    }

    private int getNewNumberForPlayer() {
        List<Integer> usedNumbers = getUsedNumbers();
        Random random = new Random();
        int number;
        do {
            number = random.nextInt(98) + 1;
        } while (usedNumbers.contains(number));
        return number;
    }

    private void updatePlayerNumber(Document lineupDoc, String playerId, int newNumber) {
        List<String> categories = Arrays.asList("forwards", "defenders", "goalies");
        for (String category : categories) {
            lineupCollection.updateOne(
                    Filters.and(
                            Filters.eq("_id", lineupDoc.getObjectId("_id")),
                            Filters.elemMatch(category, Filters.eq("id", playerId))
                    ),
                    Updates.set(category + ".$.number", newNumber)
            );
        }
    }

    private byte[] generateLineupImage(Document teamDoc, Document lineupDoc) throws IOException, URISyntaxException {
        int width = 1920;
        int height = 1080;

        // Créer l'image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Activer l'antialiasing
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Récupérer les informations de l'équipe
        String teamName = teamDoc.getString("name");
        String logoUrl = teamDoc.getString("logo");
        String colorHex = teamDoc.getString("color");

        // Convertir la couleur hex en couleur Java
        Color teamColor = Color.decode(colorHex);

        // Créer un dégradé du noir vers la couleur de l'équipe
        GradientPaint gradient = new GradientPaint(0, 0, Color.BLACK, width, height, darkerColor(teamColor));
        g.setPaint(gradient);
        g.fillRect(0, 0, width, height);

        // Charger le logo de l'équipe pour le fond (watermark)
        BufferedImage logo = ImageUtils.loadImage(logoUrl, 600);
        int logoSize = 400;
        int logoX = (width - logoSize) / 2;
        int logoY = (height - logoSize) / 2;

        // Créer une version semi-transparente du logo
        AlphaComposite alphaChannel = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f);
        g.setComposite(alphaChannel);
        g.drawImage(logo, logoX, logoY, logoSize, logoSize, null);

        // Réinitialiser l'opacité pour le reste du dessin
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        // Dessiner le titre de l'équipe
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        FontMetrics fm = g.getFontMetrics();
        int titleWidth = fm.stringWidth(teamName);
        g.drawString(teamName, (width - titleWidth) / 2, 80);

        // Dessiner une ligne sous le titre
        g.setColor(teamColor);
        g.fillRect((width - titleWidth) / 2 - 20, 90, titleWidth + 40, 5);

        // Dessiner les joueurs
        drawPlayers(g, width, height, lineupDoc, teamColor);

        g.dispose();

        // Convertir l'image en bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private void drawPlayers(Graphics2D g, int width, int height, Document lineupDoc, Color teamColor) {
        // Définir les régions pour chaque type de joueur
        int topMargin = 160;
        int bottomMargin = 40;
        int sideMargin = 50;
        int availableHeight = height - topMargin - bottomMargin;

        // Titre des sections correctement positionnés
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 30));
        g.drawString("ATTAQUANTS", sideMargin + 10, topMargin - 5);
        g.drawString("DÉFENSEURS", width / 2 + sideMargin + 10, topMargin - 5);
        g.drawString("GARDIENS", sideMargin + 10, topMargin + availableHeight * 2/3 + 40);

        // Garder la configuration exacte originale pour les attaquants
        List<Document> forwards = lineupDoc.getList("forwards", Document.class);
        int forwardWidth = width / 2 - sideMargin * 2;
        int forwardHeight = availableHeight * 2/3;

        // Dessiner les attaquants avec la méthode originale
        drawPlayerGroup(g, forwards, sideMargin, topMargin, forwardWidth, forwardHeight, 3, 4, teamColor);

        // Dessiner les défenseurs avec les MÊMES DIMENSIONS de case que les attaquants
        List<Document> defenders = lineupDoc.getList("defenders", Document.class);
        int defenderWidth = width / 2 - sideMargin * 2;  // Même largeur que les attaquants
        int defenderHeight = availableHeight * 2/3;      // Même hauteur que les attaquants
        drawPlayerGroup(g, defenders, width / 2 + sideMargin, topMargin, defenderWidth, defenderHeight, 2, 3, teamColor);

        // Dessiner les gardiens avec les MÊMES DIMENSIONS de case que les attaquants
        List<Document> goalies = lineupDoc.getList("goalies", Document.class);
        int goalieWidth = width - sideMargin * 2;        // Largeur totale disponible
        int goalieHeight = (availableHeight - 45) / 3;          // Hauteur disponible pour les gardiens
        drawPlayerGroup(g, goalies, sideMargin, topMargin + forwardHeight + 45, goalieWidth, goalieHeight, 2, 1, teamColor);
    }

    private void drawPlayerGroup(Graphics2D g, List<Document> players, int x, int y, int width, int height, int cols, int rows, Color teamColor) {
        int playerWidth = width / cols;
        int playerHeight = height / rows;

        for (int i = 0; i < players.size(); i++) {
            if (i >= cols * rows) break;
            int row = i / cols;
            int col = i % cols;
            int playerX = x + col * playerWidth;
            int playerY = y + row * playerHeight;
            Document player = players.get(i);
            drawPlayer(g, player, playerX, playerY, playerWidth, playerHeight, teamColor);
        }
    }

    private void drawPlayer(Graphics2D g, Document player, int x, int y, int width, int height, Color teamColor) {
        // Récupérer les informations du joueur
        String playerName = player.getString("name");
        int playerNumber = player.getInteger("number");
        boolean isPlaceholder = player.getBoolean("isPlaceholder", false);
        String playerId = player.getString("id");

        // Configurer les polices
        Font nameFont = new Font("Arial", Font.PLAIN, 20);
        Font numberFont = new Font("Arial", Font.BOLD, 48);
        Font placeholderFont = new Font("Arial", Font.BOLD, 60);

        // Dessiner l'encadré du joueur (exactement comme avant)
        g.setColor(new Color(0, 0, 0, 100));
        g.fillRoundRect(x + 10, y + 10, width - 20, height - 20, 15, 15);

        // Dessiner le contour avec la couleur de l'équipe
        g.setColor(isPlaceholder ? new Color(100, 100, 100) : teamColor);
        g.drawRoundRect(x + 10, y + 10, width - 20, height - 20, 15, 15);

        if (isPlaceholder) {
            // Dessiner un ? pour les placeholders
            g.setColor(new Color(150, 150, 150));
            g.setFont(placeholderFont);
            FontMetrics placeholderMetrics = g.getFontMetrics();
            int placeholderWidth = placeholderMetrics.stringWidth("?");
            g.drawString("?", x + (width - placeholderWidth) / 2, y + height / 2 + 20);
        } else {
            try {
                // Dessiner l'avatar du joueur s'il n'est pas un placeholder
                if (!playerId.startsWith("placeholder")) {
                    Member member = Objects.requireNonNull(Main.getJda().getGuildById(this.guildId)).getMemberById(playerId);
                    if (member != null) {
                        BufferedImage avatar = ImageUtils.getUserAvatar(member.getUser());
                        int avatarSize = Math.min(width - 40, 70); // Taille adaptée à la case
                        int avatarX = x + (width - avatarSize) / 2;
                        int avatarY = y + 20; // En haut de la case

                        // Rendre l'avatar rond
                        BufferedImage roundedAvatar = new BufferedImage(avatarSize, avatarSize, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2 = roundedAvatar.createGraphics();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setClip(new Ellipse2D.Float(0, 0, avatarSize, avatarSize));
                        g2.drawImage(avatar, 0, 0, avatarSize, avatarSize, null);
                        g2.dispose();

                        g.drawImage(roundedAvatar, avatarX, avatarY, null);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Erreur lors du chargement de l'avatar: {}", e.getMessage());
            }

            // Dessiner le numéro (au milieu)
            g.setColor(Color.WHITE);
            g.setFont(numberFont);
            FontMetrics numberMetrics = g.getFontMetrics();
            String numberStr = String.valueOf(playerNumber);
            int numberWidth = numberMetrics.stringWidth(numberStr);
            g.drawString(numberStr, x + (width - numberWidth) / 2, y + height / 2 + 20);

            // Dessiner le nom du joueur (en bas)
            g.setFont(nameFont);
            FontMetrics nameMetrics = g.getFontMetrics();
            int nameWidth = nameMetrics.stringWidth(playerName);

            // Si le nom est trop long, le réduire
            if (nameWidth > width - 30) {
                float newSize = 20f * (width - 30) / nameWidth;
                g.setFont(nameFont.deriveFont(Math.max(12f, newSize)));
                nameMetrics = g.getFontMetrics();
                nameWidth = nameMetrics.stringWidth(playerName);
            }

            g.drawString(playerName, x + (width - nameWidth) / 2, y + height - 20);
        }
    }

    private Color darkerColor(Color color) {
        return new Color(
                Math.max(0, color.getRed() - 50),
                Math.max(0, color.getGreen() - 50),
                Math.max(0, color.getBlue() - 50)
        );
    }
}