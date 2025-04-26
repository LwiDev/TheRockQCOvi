package ca.lwi.trqcbot.contracts;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.utils.ImageUtils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ContractManager extends ListenerAdapter {

    private static final int ENTRY_CONTRACT_DURATION_DAYS = 3; // 3 ans
    private static final int MAX_CONTRACT_YEARS = 8; // 8 ans max
    private static final int DAYS_PER_YEAR = 4; // 4 jours par année
    private static final int CONTRACT_RESPONSE_DEADLINE_DAYS = 3; // 3 jours pour répondre

    // Limites de salaire
    private static final int MIN_ENTRY_SALARY = 775000; // 775,000$ minimum
    private static final int MAX_ENTRY_SALARY = 950000; // 950,000$ maximum
    private static final int MIN_SALARY = 775000; // 775,000$ minimum
    private static final int MAX_SALARY = 14000000; // 14M$ maximum

    private static final int TWO_WAY_REPUTATION_THRESHOLD = 30;

    private final ScheduledExecutorService scheduler;

    public ContractManager() {
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::checkExpiringContracts, 0, 24, TimeUnit.HOURS);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId.startsWith("contract_")) {
            ContractManager contractManager = Main.getContractManager();
            contractManager.handleContractButtonInteraction(event);
        }
    }

    /**
     * Génère un contrat d'entrée pour un nouveau membre.
     * @param member Membre pour lequel générer un contrat d'entrée
     * @return Document contenant les informations du contrat
     */
    public Document generateEntryContract(Member member) {
        Document existingUser = Main.getMongoConnection().getDatabase().getCollection("users").find(new Document("userId", member.getId())).first();
        if (existingUser == null) return null;

        String teamName = existingUser.getString("teamName");
        int salary = MIN_ENTRY_SALARY + new Random().nextInt(MAX_ENTRY_SALARY - MIN_ENTRY_SALARY + 1);
        Date joinDate = Date.from(existingUser.getDate("joinDate").toInstant());

        // Générer un contrat d'entrée standard (3 ans, 2 volets)
        return generateContract(
                member.getId(),          // userId
                teamName,                // teamName
                salary,                  // salary
                ENTRY_CONTRACT_DURATION_DAYS, // years
                "2 volets",              // contractType
                false,                   // hasNTC
                false,                   // hasNMC
                joinDate                 // startDate - nouveau paramètre
        );
    }

    /**
     * Surcharge de la méthode generateContract qui utilise la date actuelle comme date de début
     */
    public Document generateContract(String userId, String teamName, int salary, int years, String contractType, boolean hasNTC, boolean hasNMC) {
        return generateContract(userId, teamName, salary, years, contractType, hasNTC, hasNMC, null);
    }

    /**
     * Génère un contrat générique avec les paramètres spécifiés.
     *
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     * @param salary Salaire annuel
     * @param years Durée du contrat en années
     * @param contractType Type de contrat (1 volet/2 volets)
     * @param hasNTC Si le contrat a une clause de non-échange
     * @param hasNMC Si le contrat a une clause de non-mouvement
     * @return Document représentant le contrat
     */
    public Document generateContract(String userId, String teamName, int salary, int years, String contractType, boolean hasNTC, boolean hasNMC, Date startDate) {
        int adjustedYears = Math.max(1, Math.min(years, MAX_CONTRACT_YEARS));
        int adjustedSalary = Math.max(MIN_SALARY, Math.min(salary, MAX_SALARY));
        Date contractStartDate = startDate != null ? startDate : new Date();

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(contractStartDate);
        int contractDays = adjustedYears * DAYS_PER_YEAR;
        calendar.add(Calendar.DAY_OF_MONTH, contractDays);
        Date expiryDate = calendar.getTime();

        Document contract = new Document()
                .append("_id", new ObjectId())
                .append("userId", userId)
                .append("teamName", teamName)
                .append("salary", adjustedSalary)
                .append("years", adjustedYears)
                .append("startDate", contractStartDate)
                .append("expiryDate", expiryDate)
                .append("type", contractType)
                .append("hasNTC", hasNTC)
                .append("hasNMC", hasNMC)
                .append("status", "active");

        updateUserContract(userId, contract);
        updateUserTeam(userId, teamName);
        return contract;
    }

    /**
     * Met à jour les informations de contrat d'un utilisateur.
     * @param userId ID de l'utilisateur
     * @param contract Document du contrat
     */
    private void updateUserContract(String userId, Document contract) {
        Document contractInfo = new Document()
                .append("teamName", contract.getString("teamName"))
                .append("salary", contract.getInteger("salary"))
                .append("years", contract.getInteger("years"))
                .append("startDate", contract.getDate("startDate"))
                .append("expiryDate", contract.getDate("expiryDate"))
                .append("type", contract.getString("type"))
                .append("status", contract.getString("status"));

        if (contract.containsKey("hasNTC")) {
            contractInfo.append("hasNTC", contract.getBoolean("hasNTC"));
        }
        if (contract.containsKey("hasNMC")) {
            contractInfo.append("hasNMC", contract.getBoolean("hasNMC"));
        }

        Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                new Document("userId", userId),
                new Document("$set", new Document("contract", contractInfo))
        );
    }

    /**
     * Vérifie les contrats qui expirent et envoie des notifications.
     */
    private void checkExpiringContracts() {
        Date now = new Date();

//                    Document activeContract = userData.get("contract", Document.class);
//            if (activeContract != null && activeContract.getString("status").equals("active")) {

        // Trouver tous les contrats actifs qui expirent aujourd'hui
        Document query = new Document("contract.status", "active").append("contract.expiryDate", new Document("$lte", new Date()));
        for (Document user : Main.getMongoConnection().getDatabase().getCollection("users").find(query)) {
            String userId = user.getString("userId");
            User discordUser = Main.getJda().retrieveUserById(userId).complete();
            if (discordUser != null) {
                sendContractOffers(discordUser);
                Main.getMongoConnection().getDatabase().getCollection("users")
                        .updateOne(
                                new Document("_id", user.getObjectId("_id")),
                                new Document("$set", new Document("contract.status", "expired"))
                        );
            }
        }
    }

    /**
     * Envoie des offres de contrat à un utilisateur.
     * @param user L'utilisateur à qui envoyer les offres
     */
    public void sendContractOffers(User user) {
        try {
            // Obtenir les données de l'utilisateur
            Document userData = Main.getRankManager().getUserData(user.getId());
            if (userData == null) {
                System.err.println("Données utilisateur non trouvées pour l'ID: " + user.getId());
                return;
            }

            // Récupérer l'équipe actuelle
            String currentTeam = userData.getString("teamName");

            // Obtenir deux équipes aléatoires (différentes de l'équipe actuelle)
            List<Document> randomTeams = getRandomTeams(2, Collections.singletonList(currentTeam));

            // Récupérer l'équipe actuelle depuis la DB
            Document currentTeamData = Main.getTeamManager().getTeamByName(currentTeam);

            // Préparer les 3 offres (équipe actuelle + 2 équipes aléatoires)
            List<ContractOffer> offers = new ArrayList<>();

            // Calculer le salaire en fonction de la réputation du joueur
            Document reputation = (Document) userData.getOrDefault("reputation", new Document("reputationScore", 0));
            int reputationScore = reputation.getInteger("reputationScore", 0);

            // Ajouter l'offre de l'équipe actuelle
            if (currentTeamData != null) {
                offers.add(createOfferForTeam(currentTeamData, reputationScore, true));
            }

            // Ajouter les offres des équipes aléatoires
            for (Document team : randomTeams) {
                offers.add(createOfferForTeam(team, reputationScore, false));
            }

            // Générer l'image des offres
            BufferedImage contractImage = generateContractOffersImage(offers);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(contractImage, "png", outputStream);

            // Créer les boutons pour les offres
            List<Button> buttons = new ArrayList<>();
            for (ContractOffer offer : offers) {
                buttons.add(Button.primary("contract_" + offer.teamName(), "Signer avec " + offer.teamName()));
            }
            buttons.add(Button.danger("contract_decline", "Ne pas signer"));

            // Stocker les offres en cache ou dans la base de données pour référence ultérieure
            storeContractOffers(user.getId(), offers);

            // Envoyer le message privé avec l'image et les boutons
            user.openPrivateChannel().queue(channel -> {
                channel.sendMessage("📬 Vous pouvez signer un nouveau contrat !")
                        .addFiles(FileUpload.fromData(outputStream.toByteArray(), "contract_offers.png"))
                        .addActionRow(buttons.subList(0, Math.min(buttons.size() - 1, 3)).toArray(new Button[0]))
                        .addActionRow(buttons.get(buttons.size() - 1))
                        .queue();

                channel.sendMessage("⏳ Vous avez 3 jours pour signer un contrat. Passé ce délai, les offres seront retirées " +
                                "et de nouvelles propositions moins avantageuses vous seront faites.")
                        .queue();
            });

            // Programmer une vérification dans 3 jours pour voir si l'utilisateur a signé
            scheduleContractFollowUp(user.getId());

        } catch (Exception e) {
            System.err.println("Erreur lors de l'envoi des offres de contrat à l'utilisateur " + user.getId());
            e.printStackTrace();
        }
    }

    /**
     * Crée une offre de contrat pour une équipe spécifique.
     * @param team Les données de l'équipe
     * @param reputationScore Le score de réputation du joueur
     * @param isCurrentTeam Si c'est l'équipe actuelle du joueur
     * @return Une offre de contrat
     */
    private ContractOffer createOfferForTeam(Document team, int reputationScore, boolean isCurrentTeam) {
        String teamName = team.getString("name");
        String seasonRecord = generateRandomSeasonRecord();

        // Déterminer la durée du contrat (entre 1 et 8 ans, plus de chance d'avoir un contrat plus long avec une bonne réputation)
        int maxYears = Math.max(1, Math.min(8, (reputationScore / 20) + 1));
        int years = new Random().nextInt(maxYears) + 1;

        // Calculer le salaire basé sur la réputation (min 1M, max 10M)
        // Les joueurs avec une meilleure réputation obtiennent de meilleurs salaires
        double baseSalary = 1.0 + (reputationScore / 10.0);

        // L'équipe actuelle offre généralement un peu plus (loyauté)
        if (isCurrentTeam) {
            baseSalary *= 1.1;
        }

        // Ajout d'une variation aléatoire (±10%)
        double randomFactor = 0.9 + (new Random().nextDouble() * 0.2);
        double salary = baseSalary * randomFactor;

        // S'assurer que le salaire est dans les limites
        salary = Math.max(MIN_SALARY / 1000000.0, Math.min(salary, MAX_SALARY / 1000000.0));

        // Arrondir à 2 décimales
        salary = Math.round(salary * 100.0) / 100.0;

        // Entre 1 et 15 numéros de chandail disponibles
        int jerseyNumbers = new Random().nextInt(15) + 1;

        // Le type de contrat dépend de la réputation
        String contractType;
        if (reputationScore <= TWO_WAY_REPUTATION_THRESHOLD) {
            contractType = (new Random().nextDouble() < 0.7) ? "2 volets" : "1 volet";
        } else {
            contractType = "1 volet";
        }

        // Déterminer si l'offre inclut des clauses spéciales en fonction de la réputation
        boolean hasNTC = reputationScore > 40 && new Random().nextDouble() < 0.4;
        boolean hasNMC = reputationScore > 60 && new Random().nextDouble() < 0.2;

        return new ContractOffer(teamName, years, salary, contractType, jerseyNumbers, hasNTC, hasNMC);
    }

    /**
     * Génère un bilan de saison aléatoire pour une équipe.
     * @return Une chaîne au format "W-L-OT"
     */
    private String generateRandomSeasonRecord() {
        int wins = new Random().nextInt(35) + 30; // Entre 30 et 64 victoires
        int losses = new Random().nextInt(30) + 10; // Entre 10 et 39 défaites
        int overtime = new Random().nextInt(10); // Entre 0 et 9 défaites en prolongation

        return wins + "-" + losses + "-" + overtime;
    }

    /**
     * Génère une image d'offres de contrat similaire à celle de l'exemple.
     * @param offers Liste des offres de contrat
     * @return Une image BufferedImage des offres
     */
    private BufferedImage generateContractOffersImage(List<ContractOffer> offers) throws IOException {
        int width = 900;
        int height = 450; // Augmenté pour accommoder les clauses NTC/NMC
        int panelWidth = width / 3;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Configuration de l'antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Fond noir pour l'image globale
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);

        // Dessiner chaque offre
        for (int i = 0; i < offers.size(); i++) {
            ContractOffer offer = offers.get(i);
            int x = i * panelWidth;

            // Zone du haut avec la couleur de l'équipe et logo
            Document teamData = Main.getTeamManager().getTeamByName(offer.teamName());
            Color teamColor = Color.GRAY;
            if (teamData != null && teamData.containsKey("color")) {
                String colorHex = teamData.getString("color");
                if (colorHex != null && !colorHex.isEmpty()) {
                    teamColor = Color.decode(colorHex);
                }
            }

            // Fond de couleur de l'équipe pour la partie supérieure
            g2d.setColor(teamColor);
            g2d.fillRect(x, 0, panelWidth, 150);

            // Essayer de charger et afficher le logo de l'équipe
            try {
                if (teamData != null && teamData.containsKey("logo")) {
                    String logoPath = teamData.getString("logo");
                    if (logoPath != null && !logoPath.isEmpty()) {
                        BufferedImage logo = ImageUtils.loadImage(logoPath, 80);
                        g2d.drawImage(logo, x + (panelWidth - 80) / 2, 35, 80, 80, null);
                    }
                }
            } catch (Exception e) {
                System.err.println("Erreur lors du chargement du logo de l'équipe: " + e.getMessage());
            }

            // Nom de l'équipe et record
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 16));
            String teamName = offer.teamName();
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(teamName);
            g2d.drawString(teamName, x + (panelWidth - textWidth) / 2, 170);

            // Texte "CONTRACT OFFER"
            g2d.setColor(Color.GRAY);
            g2d.setFont(new Font("Arial", Font.BOLD, 14));
            String contractText = "OFFRE DE CONTRAT";
            textWidth = g2d.getFontMetrics().stringWidth(contractText);
            g2d.drawString(contractText, x + (panelWidth - textWidth) / 2, 200);

            // Détails du contrat
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));

            // Years
            g2d.drawString("Années :", x + 50, 250);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString(String.valueOf(offer.years()), x + panelWidth - 80, 250);

            // Yearly salary
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));
            g2d.drawString("Salaire annuel :", x + 50, 270);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString("$" + String.format("%.2fM", offer.salary()), x + panelWidth - 80, 270);

            // Contract type
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));
            g2d.drawString("Type de contrat :", x + 50, 290);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString(offer.contractType(), x + panelWidth - 80, 290);

            // Number of jerseys
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));
            g2d.drawString("Nombre de 0 :", x + 50, 310);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString(String.valueOf(offer.jerseyNumbers()), x + panelWidth - 80, 310);

            // Clauses spéciales
            g2d.setFont(new Font("Arial", Font.PLAIN, 12));
            g2d.drawString("Clauses :", x + 50, 330);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            StringBuilder clauses = new StringBuilder();
            if (offer.hasNTC()) clauses.append("NTC ");
            if (offer.hasNMC()) clauses.append("NMC");
            if (clauses.length() == 0) clauses.append("Aucune");
            g2d.drawString(clauses.toString(), x + panelWidth - 80, 330);
        }

        g2d.dispose();
        return image;
    }

    /**
     * Stocke les offres de contrat pour une référence ultérieure.
     * @param userId ID de l'utilisateur
     * @param offers Liste des offres
     */
    private void storeContractOffers(String userId, List<ContractOffer> offers) {
        // Convertir les offres en documents
        List<Document> offerDocs = offers.stream()
                .map(offer -> new Document()
                        .append("teamName", offer.teamName())
                        .append("years", offer.years())
                        .append("salary", offer.salary())
                        .append("contractType", offer.contractType())
                        .append("jerseyNumbers", offer.jerseyNumbers())
                        .append("hasNTC", offer.hasNTC())
                        .append("hasNMC", offer.hasNMC()))
                .collect(Collectors.toList());

        // Date d'expiration (3 jours)
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, CONTRACT_RESPONSE_DEADLINE_DAYS);

        // Document des offres actives
        Document offersDoc = new Document()
                .append("userId", userId)
                .append("offers", offerDocs)
                .append("createdAt", new Date())
                .append("expiresAt", calendar.getTime())
                .append("status", "pending");

        // Supprimer les anciennes offres en attente pour cet utilisateur
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").deleteMany(
                new Document("userId", userId).append("status", "pending"));

        // Insérer les nouvelles offres
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").insertOne(offersDoc);
    }

    /**
     * Programme une vérification ultérieure pour voir si l'utilisateur a signé un contrat.
     * @param userId ID de l'utilisateur
     */
    private void scheduleContractFollowUp(String userId) {
        scheduler.schedule(() -> {
            // Vérifier si l'utilisateur a signé un contrat
            Document query = new Document("userId", userId)
                    .append("status", "pending");

            Document pendingOffers = Main.getMongoConnection().getDatabase().getCollection("contractOffers").find(query).first();

            if (pendingOffers != null) {
                // L'utilisateur n'a pas signé, devenir agent libre
                makeUserFreeAgent(userId);

                // Marquer les offres comme expirées
                Main.getMongoConnection().getDatabase().getCollection("contractOffers").updateOne(
                        new Document("_id", pendingOffers.getObjectId("_id")),
                        new Document("$set", new Document("status", "expired")));

                // Notifier l'utilisateur
                User user = Main.getJda().retrieveUserById(userId).complete();
                if (user != null) {
                    user.openPrivateChannel().queue(channel -> {
                        channel.sendMessage("⚠️ Vos offres de contrat ont expiré. Vous êtes maintenant un agent libre. " +
                                        "Vous pouvez demander un nouveau contrat à tout moment via la commande `/contrat`.")
                                .queue();
                    });
                }
            }
        }, CONTRACT_RESPONSE_DEADLINE_DAYS, TimeUnit.DAYS);
    }

    /**
     * Transforme un utilisateur en agent libre.
     * @param userId ID de l'utilisateur
     */
    public void makeUserFreeAgent(String userId) {
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                    new Document("_id", userData.getObjectId("_id")),
                    new Document("$set", new Document("teamName", "Agent Libre").append("contract.status", "unsigned"))
            );
        }
    }

    /**
     * Traite l'interaction avec un bouton de contrat.
     * @param event L'événement d'interaction avec le bouton
     */
    public void handleContractButtonInteraction(ButtonInteractionEvent event) {
        String[] buttonData = event.getComponentId().split("_", 2);
        if (buttonData.length < 2) return;
        String action = buttonData[0];
        String value = buttonData[1];
        if (action.equals("contract")) {
            if (value.equals("decline")) {
                makeUserFreeAgent(event.getUser().getId());
                event.reply("Vous avez refusé toutes les offres. Vous êtes maintenant un agent libre.").queue();
            } else {
                signContractWithTeam(event.getUser().getId(), value, event);
            }
        }
    }

    /**
     * Signe un contrat avec une équipe spécifique.
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     * @param event Événement d'interaction pour répondre
     */
    private void signContractWithTeam(String userId, String teamName, ButtonInteractionEvent event) {
        Document offersDoc = Main.getMongoConnection().getDatabase().getCollection("contractOffers")
                .find(new Document("userId", userId).append("status", "pending"))
                .first();
        if (offersDoc == null) {
            event.reply("Aucune offre de contrat en attente n'a été trouvée.").setEphemeral(true).queue();
            return;
        }
        List<Document> offers = offersDoc.getList("offers", Document.class);
        Document selectedOffer = null;
        for (Document offer : offers) {
            if (teamName.equals(offer.getString("teamName"))) {
                selectedOffer = offer;
                break;
            }
        }
        if (selectedOffer == null) {
            event.reply("Cette offre n'est plus disponible.").setEphemeral(true).queue();
            return;
        }

        // Créer le nouveau contrat
        int years = selectedOffer.getInteger("years");
        double salaryInMillions = selectedOffer.getDouble("salary");
        String contractType = selectedOffer.getString("contractType");
        boolean hasNTC = selectedOffer.getBoolean("hasNTC", false);
        boolean hasNMC = selectedOffer.getBoolean("hasNMC", false);

        // Créer le contrat avec la fonction générique
        Document contract = generateContract(
                userId,
                teamName,
                (int)(salaryInMillions * 1000000), // Convertir en dollars
                years,
                contractType,
                hasNTC,
                hasNMC
        );

        // Marquer les offres comme acceptées
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").updateOne(
                new Document("_id", offersDoc.getObjectId("_id")),
                new Document("$set", new Document("status", "accepted")
                        .append("acceptedTeam", teamName)));

        // Répondre à l'utilisateur
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH);

        StringBuilder clausesText = new StringBuilder();
        if (hasNTC || hasNMC) {
            clausesText.append(" avec les clauses: ");
            if (hasNTC) clausesText.append("non-échange ");
            if (hasNMC) clausesText.append("non-mouvement");
        }

        event.reply("🎉 Félicitations ! Vous avez signé un contrat de " + years + " ans avec " + teamName +
                " pour un salaire annuel de $" + String.format("%.2fM", salaryInMillions) + clausesText.toString() + "." +
                "\nLe contrat expire le " + dateFormat.format(contract.getDate("expiryDate")) + ".").queue();
    }

    /**
     * Met à jour l'équipe d'un utilisateur dans la base de données.
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     */
    private void updateUserTeam(String userId, String teamName) {
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                    new Document("_id", userData.getObjectId("_id")),
                    new Document("$set", new Document("teamName", teamName)));
        }
    }

    /**
     * Met à jour l'équipe d'un membre Discord dans la base de données.
     * @param member Membre Discord
     * @param teamName Nom de l'équipe
     */
    private void updateUserTeam(Member member, String teamName) {
        updateUserTeam(member.getId(), teamName);
    }

    /**
     * Obtient une équipe aléatoire de la base de données.
     * @return Document de l'équipe
     */
    private Document getRandomTeam() {
        List<Document> teams = new ArrayList<>();
        Main.getMongoConnection().getDatabase().getCollection("teams").find().into(teams);

        if (teams.isEmpty()) {
            return null;
        }

        return teams.get(new Random().nextInt(teams.size()));
    }

    /**
     * Obtient plusieurs équipes aléatoires, en excluant certaines.
     * @param count Nombre d'équipes à obtenir
     * @param exclude Liste des noms d'équipes à exclure
     * @return Liste de documents d'équipes
     */
    private List<Document> getRandomTeams(int count, List<String> exclude) {
        List<Document> allTeams = new ArrayList<>();
        Main.getMongoConnection().getDatabase().getCollection("teams").find().into(allTeams);

        // Filtrer les équipes exclues
        List<Document> availableTeams = allTeams.stream()
                .filter(team -> !exclude.contains(team.getString("name")))
                .collect(Collectors.toList());

        if (availableTeams.size() <= count) {
            return availableTeams;
        }

        // Sélectionner aléatoirement "count" équipes
        List<Document> selectedTeams = new ArrayList<>();
        Random random = new Random();

        while (selectedTeams.size() < count && !availableTeams.isEmpty()) {
            int index = random.nextInt(availableTeams.size());
            selectedTeams.add(availableTeams.remove(index));
        }

        return selectedTeams;
    }

    /**
     * Classe interne représentant une offre de contrat.
     * @param teamName Nom de l'équipe
     * @param years Durée du contrat en années
     * @param salary Salaire annuel en millions
     * @param contractType Type de contrat (1 volet/2 volets)
     * @param jerseyNumbers Nombre de chandails disponibles
     * @param hasNTC Contrat avec clause de non-échange
     * @param hasNMC Contrat avec clause de non-mouvement
     */
    public record ContractOffer(String teamName, int years, double salary, String contractType, int jerseyNumbers, boolean hasNTC, boolean hasNMC) {}
}