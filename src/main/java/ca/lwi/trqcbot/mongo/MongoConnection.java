package ca.lwi.trqcbot.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public class MongoConnection {

    public static MongoConnection instance;
    private final MongoCredentials mongoCredentials;
    private MongoClient mongoClient;

    public MongoConnection(MongoCredentials mongoCredentials) {
        instance = this;
        this.mongoCredentials = mongoCredentials;
    }

    public void init() {
        try {
            String strUri = String.format("mongodb+srv://%s:%s@%s/?retryWrites=true&w=majority", mongoCredentials.getUsername(), mongoCredentials.getPassword(), mongoCredentials.getIp());
            System.out.println("Attempting to connect to MongoDB with URI: " + strUri.replace(mongoCredentials.getPassword(), "****")); // Log sécurisé
            mongoClient = MongoClients.create(strUri);
            mongoClient.listDatabaseNames().first();
            System.out.println("Successfully connected to MongoDB Atlas!");
        } catch (Exception e) {
            System.err.println("Failed to connect to MongoDB Atlas: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize MongoDB connection", e);
        }
    }

    public MongoClient getMongoClient() {
        if (mongoClient == null) {
            throw new IllegalStateException("MongoDB client not initialized. Call init() first.");
        }
        return mongoClient;
    }

    public MongoDatabase getDatabase() {
        return getMongoClient().getDatabase(mongoCredentials.getDatabase());
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
            mongoClient = null;
        }
    }
}
