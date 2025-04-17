package ca.lwi.trqcbot.commands.list;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.commands.Command;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command.Choice;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ComNumero extends Command {

    private final MongoCollection<Document> usersCollection;
    private final MongoCollection<Document> teamsCollection;
    private final MongoCollection<Document> lineupCollection;
    private final ComTeam teamCommand;

    private static final Logger LOGGER = LoggerFactory.getLogger(ComNumero.class);

    public ComNumero(ComTeam teamCommand) {
        super("numero", "Changer votre numéro (vétérans seulement)");
        MongoDatabase database = Main.getMongoConnection().getDatabase();
        this.usersCollection = database.getCollection("users");
        this.teamsCollection = database.getCollection("teams");
        this.lineupCollection = database.getCollection("teams_lineup");
        this.teamCommand = teamCommand;
        
        addOptions(new OptionData(OptionType.INTEGER, "numero", "Numéro désiré", true).setMinValue(1).setMaxValue(98));
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        Member member = e.getMember();
        if (member == null) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }
        
        int requestedNumber = Objects.requireNonNull(e.getOption("numero")).getAsInt();
        
        if (!teamCommand.isVeteran(member)) {
            e.reply("❌ Seuls les vétérans peuvent choisir leur numéro.").setEphemeral(true).queue();
            return;
        }

        Document userDoc = usersCollection.find(Filters.eq("userId", member.getId())).first();
        if (userDoc == null || !userDoc.containsKey("teamName")) {
            e.reply("❌ Vous n'êtes pas associé à une équipe.").setEphemeral(true).queue();
            return;
        }

        String teamName = userDoc.getString("teamName");
        Document teamDoc = teamsCollection.find(Filters.eq("name", teamName)).first();
        if (teamDoc == null) {
            e.reply("❌ Cette équipe n'existe pas.").setEphemeral(true).queue();
            return;
        }
        
        // Vérifier si le numéro est valide (1-98)
        if (requestedNumber < 1 || requestedNumber > 98) {
            e.reply("❌ Le numéro doit être entre 1 et 98. Le numéro 99 est retiré dans toute la ligue.").setEphemeral(true).queue();
            return;
        }

        e.deferReply(true).queue();

        try {
            teamCommand.setVeteranNumber(member, requestedNumber, teamName, e);

            // Récupérer l'état actuel du joueur pour confirmer le numéro
            Document lineupDoc = lineupCollection.find(Filters.eq("teamName", teamName)).first();
            if (lineupDoc != null) {
                String playerId = member.getId();
                int currentNumber = -1;
                List<String> categories = List.of("forwards", "defenders", "goalies");
                for (String category : categories) {
                    List<Document> players = lineupDoc.getList(category, Document.class);
                    if (players != null) {
                        for (Document player : players) {
                            if (playerId.equals(player.getString("id"))) {
                                currentNumber = player.getInteger("number");
                                break;
                            }
                        }
                    }
                    if (currentNumber != -1) break;
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", "numero", ex.getMessage());
            e.getHook().sendMessage("❌ Une erreur est survenue lors du traitement de votre demande.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) {
        if (e.getFocusedOption().getName().equals("numero")) {
            String query = e.getFocusedOption().getValue();
            List<Choice> choices = IntStream.of(1, 8, 9, 10, 13, 14, 19, 21, 22, 23, 27, 29, 30, 31, 33, 35, 40, 41, 55, 66, 72, 87, 88, 91, 97)
                    .filter(num -> Integer.toString(num).startsWith(query))
                    .mapToObj(num -> new Choice(Integer.toString(num), num))
                    .limit(25)
                    .collect(Collectors.toList());
            e.replyChoices(choices).queue();
        }
    }
}