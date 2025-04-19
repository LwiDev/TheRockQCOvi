package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ComBrackets extends Command {

    private final MongoCollection<Document> teamsCollection;
    private final MongoCollection<Document> bracketsCollection;
    private final MongoCollection<Document> playoffsCollection;
    private final String bracketsChannelId;
    private final String resultsChannelId;

    private static final Logger LOGGER = LoggerFactory.getLogger(ComBrackets.class);

    // Structure des playoffs NHL
    private static final String[] ROUNDS = {"R1", "R2", "Conference Final", "Finals"};
    // Équipes pour chaque conférence selon les images fournies
    private static final String[][] EASTERN_TEAMS = {
            {"TOR", "OTT"},  // R1 Match 1
            {"TBL", "FLA"},  // R1 Match 2
            {"WSH", "MTL"},  // R1 Match 3
            {"CAR", "NJD"}   // R1 Match 4
    };
    private static final String[][] WESTERN_TEAMS = {
            {"WPG", "STL"},  // R1 Match 1
            {"DAL", "COL"},  // R1 Match 2
            {"VGK", "MIN"},  // R1 Match 3
            {"LAK", "EDM"}   // R1 Match 4
    };

    public ComBrackets() {
        super("brackets", "Système de prédictions pour les séries de la NHL");

        setDefaultPermissions(DefaultMemberPermissions.ENABLED);

        OptionData actionOption = new OptionData(OptionType.STRING, "action", "Action à effectuer", true);
        actionOption.addChoice("voir", "voir");
        actionOption.addChoice("prédire", "prédire");
        addOptions(actionOption);

        // Option pour le matchup (série)
        addOption(OptionType.STRING, "matchup", "La série à prédire", false);

        // Option pour l'équipe gagnante
        addOption(OptionType.STRING, "gagnant", "L'équipe qui gagnera la série", false);

        // Option pour le nombre de matchs
        OptionData gamesOption = new OptionData(OptionType.INTEGER, "matchs", "Nombre de matchs dans la série", false);
        for (int i = 4; i <= 7; i++) {
            gamesOption.addChoice("En " + i, i);
        }
        addOptions(gamesOption);

        // Option pour voir les prédictions d'un autre utilisateur
        addOption(OptionType.USER, "utilisateur", "Voir les prédictions d'un autre utilisateur", false);

        // Option administrative pour mettre à jour le score d'un matchup
        OptionData adminActionOption = new OptionData(OptionType.STRING, "action", "Action à effectuer", true);
        adminActionOption.addChoice("update_score", "update_score");
        adminActionOption.addChoice("set_round", "set_round");
        addOptions(adminActionOption);

        addOption(OptionType.STRING, "team1", "Première équipe", false);
        addOption(OptionType.INTEGER, "score1", "Score de la première équipe", false);
        addOption(OptionType.STRING, "team2", "Deuxième équipe", false);
        addOption(OptionType.INTEGER, "score2", "Score de la deuxième équipe", false);
        addOption(OptionType.INTEGER, "round", "Numéro du round (1, 2, 3, 4)", false);

        MongoDatabase database = Main.getMongoConnection().getDatabase();
        this.teamsCollection = database.getCollection("teams");
        this.bracketsCollection = database.getCollection("brackets");
        this.playoffsCollection = database.getCollection("playoffs");

        Dotenv dotenv = Dotenv.load();
        this.bracketsChannelId = dotenv.get("CHANNEL_BRACKETS_ID");
        this.resultsChannelId = dotenv.get("CHANNEL_RESULTS_BRACKETS_ID");

        // Initialiser le document de configuration des playoffs s'il n'existe pas
        initPlayoffsConfig();
    }

    private void initPlayoffsConfig() {
        // Vérifier si la configuration des playoffs existe
        Document config = playoffsCollection.find(new Document("_id", "config")).first();

        if (config == null) {
            // Créer la configuration initiale
            Document initialConfig = new Document("_id", "config")
                    .append("currentRound", 1)
                    .append("matchups", createInitialMatchups());

            playoffsCollection.insertOne(initialConfig);
        }
    }

    private List<Document> createInitialMatchups() {
        List<Document> matchups = new ArrayList<>();
        // Ajouter les matchups de la conférence Est
        for (int i = 0; i < EASTERN_TEAMS.length; i++) {
            String[] teams = EASTERN_TEAMS[i];
            matchups.add(new Document()
                    .append("conference", "Eastern")
                    .append("round", 1)
                    .append("matchupId", "E" + (i + 1))
                    .append("team1", teams[0])
                    .append("team2", teams[1])
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));
        }
        // Ajouter les matchups de la conférence Ouest
        for (int i = 0; i < WESTERN_TEAMS.length; i++) {
            String[] teams = WESTERN_TEAMS[i];
            matchups.add(new Document()
                    .append("conference", "Western")
                    .append("round", 1)
                    .append("matchupId", "W" + (i + 1))
                    .append("team1", teams[0])
                    .append("team2", teams[1])
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));
        }
        return matchups;
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        Member member = e.getMember();
        if (member == null) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        // Vérifier le canal pour les actions non-admin
        String action = e.getOption("action") != null ? e.getOption("action").getAsString() : null;
        String adminAction = e.getOption("admin_action") != null ? e.getOption("admin_action").getAsString() : null;

        if (adminAction == null && action != null) {
            if (bracketsChannelId != null && !e.getChannelId().equals(bracketsChannelId)) {
                e.reply("> 🛑 Cette commande ne peut être utilisée ici.\n" +
                        "> Dirige-toi vers le salon <#" + bracketsChannelId + "> pour utiliser /brackets.").setEphemeral(true).queue();
                return;
            }
        } else if (adminAction != null) {
            // Vérifier les permissions d'administrateur
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                e.reply("Vous n'avez pas les permissions nécessaires pour exécuter cette action.").setEphemeral(true).queue();
                return;
            }

            // Actions administratives
            switch (adminAction) {
                case "update_score":
                    handleUpdateScore(e);
                    break;
                case "set_round":
                    handleSetRound(e);
                    break;
                default:
                    e.reply("Action administrative non reconnue.").setEphemeral(true).queue();
            }
            return;
        }

        if (action == null) {
            e.reply("Veuillez spécifier une action à effectuer.").setEphemeral(true).queue();
            return;
        }

        // Actions utilisateur
        switch (action) {
            case "voir":
                handleViewBrackets(e);
                break;
            case "prédire":
                handlePrediction(e);
                break;
            default:
                e.reply("Action non reconnue.").setEphemeral(true).queue();
        }
    }

    private void handleViewBrackets(SlashCommandInteractionEvent e) {
        e.deferReply().queue();

        // Déterminer l'utilisateur cible
        User targetUser = e.getOption("utilisateur") != null ? e.getOption("utilisateur").getAsUser() : e.getUser();
        String userId = targetUser.getId();

        // Récupérer les prédictions de l'utilisateur
        Document userBrackets = bracketsCollection.find(new Document("userId", userId)).first();

        // Récupérer la configuration actuelle des playoffs
        Document playoffsConfig = playoffsCollection.find(new Document("_id", "config")).first();

        if (userBrackets == null) {
            e.getHook().sendMessage("Aucune prédiction trouvée pour " + (targetUser.equals(e.getUser()) ? "vous" : targetUser.getName()) + ".").queue();
            return;
        }

        try {
            // Générer l'image des brackets
            ByteArrayOutputStream outputStream = generateBracketsImage(userBrackets, playoffsConfig, targetUser);
            String filename = targetUser.getName() + "_brackets.png";

            e.getHook().sendFiles(FileUpload.fromData(outputStream.toByteArray(), filename))
                    .queue();

        } catch (Exception ex) {
            LOGGER.error("Erreur lors de la génération de l'image des brackets", ex);
            e.getHook().sendMessage("Une erreur est survenue lors de la génération de l'image des brackets.").queue();
        }
    }

    private void handlePrediction(SlashCommandInteractionEvent e) {
        // Vérifier que tous les paramètres nécessaires sont présents
        OptionMapping matchupOption = e.getOption("matchup");
        OptionMapping winnerOption = e.getOption("gagnant");
        OptionMapping gamesOption = e.getOption("matchs");

        if (matchupOption == null || winnerOption == null || gamesOption == null) {
            e.reply("Veuillez spécifier le matchup, l'équipe gagnante et le nombre de matchs.").setEphemeral(true).queue();
            return;
        }

        String matchupId = matchupOption.getAsString();
        String winner = winnerOption.getAsString();
        int numGames = gamesOption.getAsInt();

        // Récupérer la configuration actuelle des séries
        Document playoffsConfig = playoffsCollection.find(new Document("_id", "config")).first();
        if (playoffsConfig == null) {
            e.reply("Erreur: Configuration des séries introuvable.").setEphemeral(true).queue();
            return;
        }

        int currentRound = playoffsConfig.getInteger("currentRound");

        // Vérifier si le matchup existe et appartient au round actuel
        List<Document> matchups = (List<Document>) playoffsConfig.get("matchups");
        boolean validMatchup = false;
        boolean validTeam = false;

        for (Document matchup : matchups) {
            if (matchup.getInteger("round") == currentRound &&
                    matchup.getString("matchupId").equals(matchupId)) {

                validMatchup = true;
                String team1 = matchup.getString("team1");
                String team2 = matchup.getString("team2");

                if (winner.equals(team1) || winner.equals(team2)) {
                    validTeam = true;
                    break;
                }
            }
        }

        if (!validMatchup) {
            e.reply("Ce matchup n'est pas valide pour le round actuel.").setEphemeral(true).queue();
            return;
        }

        if (!validTeam) {
            e.reply("L'équipe spécifiée ne participe pas à ce matchup.").setEphemeral(true).queue();
            return;
        }

        // Enregistrer la prédiction
        String userId = e.getUser().getId();
        Document userBrackets = bracketsCollection.find(new Document("userId", userId)).first();

        if (userBrackets == null) {
            // Créer un nouveau document pour l'utilisateur
            userBrackets = new Document("userId", userId)
                    .append("username", e.getUser().getName())
                    .append("predictions", new Document());
        }

        Document predictions = (Document) userBrackets.get("predictions");
        String predictionKey = "R" + currentRound + "_" + matchupId;

        predictions.put(predictionKey, new Document()
                .append("winner", winner)
                .append("games", numGames));

        // Mettre à jour ou insérer dans la base de données
        bracketsCollection.replaceOne(
                new Document("userId", userId),
                userBrackets,
                new com.mongodb.client.model.ReplaceOptions().upsert(true));

        e.reply("Prédiction enregistrée pour le matchup " + matchupId + " : " + winner + " en " + numGames + " matchs.").queue();
    }

    private void handleUpdateScore(SlashCommandInteractionEvent e) {
        OptionMapping equipe1Option = e.getOption("equipe1");
        OptionMapping score1Option = e.getOption("score1");
        OptionMapping equipe2Option = e.getOption("equipe2");
        OptionMapping score2Option = e.getOption("score2");

        if (equipe1Option == null || score1Option == null || equipe2Option == null || score2Option == null) {
            e.reply("Veuillez spécifier les deux équipes et leurs scores.").setEphemeral(true).queue();
            return;
        }

        String equipe1 = equipe1Option.getAsString();
        int score1 = score1Option.getAsInt();
        String equipe2 = equipe2Option.getAsString();
        int score2 = score2Option.getAsInt();

        // Récupérer la configuration des playoffs
        Document playoffsConfig = playoffsCollection.find(new Document("_id", "config")).first();
        if (playoffsConfig == null) {
            e.reply("Erreur: Configuration des séries introuvable.").setEphemeral(true).queue();
            return;
        }

        // Mettre à jour le score du matchup correspondant
        List<Document> matchups = (List<Document>) playoffsConfig.get("matchups");
        boolean matchupFound = false;

        for (int i = 0; i < matchups.size(); i++) {
            Document matchup = matchups.get(i);
            String team1 = matchup.getString("team1");
            String team2 = matchup.getString("team2");

            if ((team1.equals(equipe1) && team2.equals(equipe2)) ||
                    (team1.equals(equipe2) && team2.equals(equipe1))) {

                // Mettre à jour les scores
                if (team1.equals(equipe1)) {
                    matchup.put("score1", score1);
                    matchup.put("score2", score2);
                } else {
                    matchup.put("score1", score2);
                    matchup.put("score2", score1);
                }

                // Vérifier si une équipe a gagné (score de 4)
                if (score1 == 4 || score2 == 4) {
                    String winner = score1 == 4 ? equipe1 : equipe2;
                    matchup.put("winner", winner);
                    matchup.put("completed", true);

                    // Vérifier si on peut passer automatiquement au prochain round
                    checkAndAdvanceRound(playoffsConfig, matchups);
                }

                matchupFound = true;
                break;
            }
        }

        if (!matchupFound) {
            e.reply("Aucun matchup trouvé pour les équipes spécifiées.").setEphemeral(true).queue();
            return;
        }

        // Mettre à jour la configuration
        playoffsCollection.replaceOne(
                new Document("_id", "config"),
                playoffsConfig);

        // Publier le résultat dans le canal de résultats si disponible
        if (resultsChannelId != null) {
            e.getJDA().getTextChannelById(resultsChannelId).sendMessage(
                    "**Mise à jour des scores** : " + equipe1 + " " + score1 + " - " + score2 + " " + equipe2).queue();
        }

        e.reply("Score mis à jour avec succès.").queue();
    }

    private void handleSetRound(SlashCommandInteractionEvent e) {
        OptionMapping roundOption = e.getOption("round");

        if (roundOption == null) {
            e.reply("Veuillez spécifier le numéro du round.").setEphemeral(true).queue();
            return;
        }

        int roundNumber = roundOption.getAsInt();

        if (roundNumber < 1 || roundNumber > 4) {
            e.reply("Le numéro du round doit être entre 1 et 4.").setEphemeral(true).queue();
            return;
        }

        // Mettre à jour le round actuel
        Document playoffsConfig = playoffsCollection.find(new Document("_id", "config")).first();
        if (playoffsConfig == null) {
            e.reply("Erreur: Configuration des séries introuvable.").setEphemeral(true).queue();
            return;
        }

        playoffsConfig.put("currentRound", roundNumber);

        // Si on passe à un nouveau round, créer automatiquement les matchups si nécessaire
        if (roundNumber > 1) {
            createNextRoundMatchups(playoffsConfig, roundNumber);
        }

        playoffsCollection.replaceOne(
                new Document("_id", "config"),
                playoffsConfig);

        // Annoncer le changement de round dans le canal de résultats
        if (resultsChannelId != null) {
            e.getJDA().getTextChannelById(resultsChannelId).sendMessage("**Les séries passent au Round " + roundNumber + " : " + ROUNDS[roundNumber-1] + "**").queue();
        }

        e.reply("Round mis à jour avec succès.").queue();
    }

    private void checkAndAdvanceRound(Document playoffsConfig, List<Document> matchups) {
        int currentRound = playoffsConfig.getInteger("currentRound");

        // Vérifier si tous les matchups du round actuel sont terminés
        boolean allCompleted = true;
        for (Document matchup : matchups) {
            if (matchup.getInteger("round") == currentRound && !matchup.getBoolean("completed", false)) {
                allCompleted = false;
                break;
            }
        }

        // Si tous les matchups sont terminés et qu'on n'est pas à la finale
        if (allCompleted && currentRound < 4) {
            // Créer les matchups du prochain round
            createNextRoundMatchups(playoffsConfig, currentRound + 1);
        }
    }

    private void createNextRoundMatchups(Document playoffsConfig, int nextRound) {
        List<Document> matchups = (List<Document>) playoffsConfig.get("matchups");

        // Filtrer les matchups terminés du round précédent
        List<Document> previousRoundMatchups = new ArrayList<>();
        for (Document matchup : matchups) {
            if (matchup.getInteger("round") == nextRound - 1 && matchup.getBoolean("completed", false)) {
                previousRoundMatchups.add(matchup);
            }
        }

        // Pour chaque conférence, créer les nouveaux matchups
        if (nextRound == 2) {
            // R1 à R2: 8 matchups -> 4 matchups (2 par conférence)
            createR2Matchups(matchups, previousRoundMatchups, "Eastern");
            createR2Matchups(matchups, previousRoundMatchups, "Western");
        } else if (nextRound == 3) {
            // R2 à R3: 4 matchups -> 2 matchups (finales de conférence)
            createR3Matchups(matchups, previousRoundMatchups, "Eastern");
            createR3Matchups(matchups, previousRoundMatchups, "Western");
        } else if (nextRound == 4) {
            // R3 à Finals: 2 matchups -> 1 matchup (finale)
            createFinalsMatchup(matchups, previousRoundMatchups);
        }
    }

    private void createR2Matchups(List<Document> allMatchups, List<Document> previousRoundMatchups, String conference) {
        List<String> winners = new ArrayList<>();

        // Récupérer les gagnants de la conférence spécifiée
        for (Document matchup : previousRoundMatchups) {
            if (matchup.getString("conference").equals(conference)) {
                winners.add(matchup.getString("winner"));
            }
        }

        // Créer les matchups R2 si on a assez de gagnants
        if (winners.size() >= 4) {
            // Matchup 1: vainqueur de E1/W1 vs vainqueur de E2/W2
            allMatchups.add(new Document()
                    .append("conference", conference)
                    .append("round", 2)
                    .append("matchupId", conference.substring(0, 1) + "1")
                    .append("team1", winners.get(0))
                    .append("team2", winners.get(1))
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));

            // Matchup 2: vainqueur de E3/W3 vs vainqueur de E4/W4
            allMatchups.add(new Document()
                    .append("conference", conference)
                    .append("round", 2)
                    .append("matchupId", conference.substring(0, 1) + "2")
                    .append("team1", winners.get(2))
                    .append("team2", winners.get(3))
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));
        }
    }

    private void createR3Matchups(List<Document> allMatchups, List<Document> previousRoundMatchups, String conference) {
        List<String> winners = new ArrayList<>();

        // Récupérer les gagnants de la conférence spécifiée
        for (Document matchup : previousRoundMatchups) {
            if (matchup.getString("conference").equals(conference) && matchup.getInteger("round") == 2) {
                winners.add(matchup.getString("winner"));
            }
        }

        // Créer le matchup R3 (finale de conférence) si on a assez de gagnants
        if (winners.size() >= 2) {
            allMatchups.add(new Document()
                    .append("conference", conference)
                    .append("round", 3)
                    .append("matchupId", conference.substring(0, 1) + "CF")
                    .append("team1", winners.get(0))
                    .append("team2", winners.get(1))
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));
        }
    }

    private void createFinalsMatchup(List<Document> allMatchups, List<Document> previousRoundMatchups) {
        String easternWinner = null;
        String westernWinner = null;

        // Récupérer les gagnants des finales de conférence
        for (Document matchup : previousRoundMatchups) {
            if (matchup.getInteger("round") == 3) {
                if (matchup.getString("conference").equals("Eastern")) {
                    easternWinner = matchup.getString("winner");
                } else if (matchup.getString("conference").equals("Western")) {
                    westernWinner = matchup.getString("winner");
                }
            }
        }

        // Créer le matchup de la finale si on a les deux gagnants
        if (easternWinner != null && westernWinner != null) {
            allMatchups.add(new Document()
                    .append("round", 4)
                    .append("matchupId", "SCF")
                    .append("team1", easternWinner)
                    .append("team2", westernWinner)
                    .append("score1", 0)
                    .append("score2", 0)
                    .append("winner", null)
                    .append("completed", false));
        }
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) {
        String focusedOption = e.getFocusedOption().getName();

        if (focusedOption.equals("matchup")) {
            // Récupérer la configuration des playoffs pour obtenir les matchups actuels
            Document config = playoffsCollection.find(new Document("_id", "config")).first();
            if (config == null) return;

            int currentRound = config.getInteger("currentRound");
            List<Document> matchups = (List<Document>) config.get("matchups");
            List<String> matchupChoices = new ArrayList<>();

            for (Document matchup : matchups) {
                if (matchup.getInteger("round") == currentRound && !matchup.getBoolean("completed", false)) {
                    String matchupId = matchup.getString("matchupId");
                    String team1 = matchup.getString("team1");
                    String team2 = matchup.getString("team2");
                    matchupChoices.add(matchupId);
                }
            }

            e.replyChoiceStrings(matchupChoices).queue();
        } else if (focusedOption.equals("gagnant") || focusedOption.equals("equipe1") || focusedOption.equals("equipe2")) {
            String query = e.getFocusedOption().getValue().toLowerCase();

            // Pour le choix d'équipe dans une prédiction, proposer les équipes du matchup sélectionné
            if (focusedOption.equals("gagnant")) {
                OptionMapping matchupOption = e.getOption("matchup");
                if (matchupOption != null) {
                    String matchupId = matchupOption.getAsString();

                    Document config = playoffsCollection.find(new Document("_id", "config")).first();
                    if (config != null) {
                        List<Document> matchups = (List<Document>) config.get("matchups");

                        for (Document matchup : matchups) {
                            if (matchup.getString("matchupId").equals(matchupId)) {
                                List<String> teams = Arrays.asList(
                                        matchup.getString("team1"),
                                        matchup.getString("team2")
                                );
                                e.replyChoiceStrings(teams).queue();
                                return;
                            }
                        }
                    }
                }
            }

            // Sinon, proposer toutes les équipes qui correspondent à la requête
            List<String> teamChoices = new ArrayList<>();
            try (MongoCursor<Document> cursor = teamsCollection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document team = cursor.next();
                    String abbr = team.getString("abbreviation");
                    String name = team.getString("name");

                    if ((abbr != null && abbr.toLowerCase().contains(query)) ||
                            (name != null && name.toLowerCase().contains(query))) {
                        teamChoices.add(abbr);
                    }

                    if (teamChoices.size() >= 25) break; // Limite de choix
                }
            }

            e.replyChoiceStrings(teamChoices).queue();
        }
    }
}