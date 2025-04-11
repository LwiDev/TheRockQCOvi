package ca.lwi.trqcbot.teams;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import ca.lwi.trqcbot.Main;

public class TeamManager {

    private final MongoCollection<Document> teamsCollection;

    public TeamManager() {
        this.teamsCollection = Main.getMongoConnection().getDatabase().getCollection("teams");
    }

    public Document getTeamByName(String name) {
        return teamsCollection.find(Filters.eq("name", name)).first();
    }
}
