package ca.lwi.trqcbot.teams;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public class TeamManager {

    private final MongoDatabase database;
    private final MongoCollection<Document> teamsCollection;

    public TeamManager() {
        this.database = Main.getMongoConnection().getDatabase();
        this.teamsCollection = this.database.getCollection("teams");
    }

    public Document getTeamByName(String name) {
        return teamsCollection.find(Filters.eq("name", name)).first();
    }

    public List<Document> getAllTeams() {
        MongoCollection<Document> collection = this.database.getCollection("teams");
        return collection.find().into(new ArrayList<>());
    }

    public List<Document> getAllUsersFromTeam(String teamName) {
        MongoCollection<Document> collection = this.database.getCollection("users");
        return collection.find(Filters.eq("teamName", teamName)).into(new ArrayList<>());
    }
}
