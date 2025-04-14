package ca.lwi.trqcbot.ressources;

import ca.lwi.trqcbot.Main;
import com.mongodb.client.MongoCollection;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ResourcesManager {

    private final Logger LOGGER = LoggerFactory.getLogger(ResourcesManager.class);
    private boolean listenersRegistered = false;

    private final String COLLECTION_NAME = "resources";

    // Section keys for MongoDB
    private final String SECTION_ROLES = "roles";
    private final String SECTION_CATEGORIES = "categories";
    private final String SECTION_CHANNELS = "importantChannels";
    private final String SECTION_LINKS = "usefulLinks";

    // Component IDs
    private final String SELECT_SECTION = "resources_section_select";
    private final String SELECT_CHANNEL = "resources_channel_select";
    private final String SELECT_ROLE = "resources_role_select";
    private final String BTN_ADD = "resources_add_item";
    private final String BTN_BACK_TO_SECTIONS = "resources_back_to_sections";
    private final String BTN_EDIT = "resources_edit_item";
    private final String BTN_DELETE = "resources_delete_item";
    private final String BTN_SERVER_NAME = "resources_server_name";

    // Modal IDs
    private final String MODAL_ADD = "resources_add_modal";
    private final String MODAL_EDIT = "resources_edit_modal";
    private final String MODAL_DELETE = "resources_delete_modal";
    private final String MODAL_SERVER_NAME = "resources_server_name_modal";
    private final String MODAL_ADD_WITH_SELECTOR = "resources_add_with_selector";

    // Cache section choice for each user
    private final Map<String, String> userSectionChoices = new HashMap<>();

    public void registerEventListeners(net.dv8tion.jda.api.JDA jda) {
        if (!this.listenersRegistered) {
            jda.addEventListener(
                    new ResourcesButtonListener(),
                    new ResourcesSelectMenuListener(),
                    new ResourcesModalListener()
            );
            this.listenersRegistered = true;
        }
    }

    public void handleUpdate(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();

        ResourcesMessageHandler.updateResourcesMessages(forceNew)
                .thenAccept(v -> e.getHook().sendMessage("✅ Le message de ressources ont été mis à jour.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                }))
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour des messages : {}", error.getMessage(), error);
                    e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue();
                    return null;
                });
    }

    public void handleManage(SlashCommandInteractionEvent e) {
        // Create section selector
        StringSelectMenu sectionSelect = StringSelectMenu.create(SELECT_SECTION)
                .setPlaceholder("Choisir la section à modifier")
                .addOption("🎭 Rôles", SECTION_ROLES, "Gérer les rôles affichés dans l'embed")
                .addOption("📂 Catégories", SECTION_CATEGORIES, "Gérer les catégories affichées dans l'embed")
                .addOption("📌 Salons importants", SECTION_CHANNELS, "Gérer les salons importants affichés dans l'embed")
                .addOption("🔗 Liens utiles", SECTION_LINKS, "Gérer les liens utiles affichés dans l'embed")
                .build();

        // Create buttons for actions
        Button addButton = Button.primary(BTN_ADD, "Ajouter").asDisabled();
        Button editButton = Button.secondary(BTN_EDIT, "Modifier").asDisabled();
        Button deleteButton = Button.danger(BTN_DELETE, "Supprimer").asDisabled();
        Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

        e.reply("👋 **Interface de gestion des ressources**\n" +
                        "Sélectionnez d'abord une section, puis choisissez l'action à effectuer.")
                .addComponents(
                        ActionRow.of(sectionSelect),
                        ActionRow.of(addButton, editButton, deleteButton),
                        ActionRow.of(serverNameButton)
                )
                .setEphemeral(true)
                .queue();
    }

    public void handleViewConfig(SlashCommandInteractionEvent e) {
        MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
        Document configDoc = collection.find(new Document("type", "config")).first();
        if (configDoc == null) {
            e.getHook().sendMessage("❌ Configuration non trouvée. Veuillez d'abord exécuter /tr8 resources update.").queue();
            return;
        }

        String serverName = configDoc.getString("serverName");
        List<Document> roles = configDoc.getList(SECTION_ROLES, Document.class, new ArrayList<>());
        List<Document> categories = configDoc.getList(SECTION_CATEGORIES, Document.class, new ArrayList<>());
        List<Document> channels = configDoc.getList(SECTION_CHANNELS, Document.class, new ArrayList<>());
        List<Document> links = configDoc.getList(SECTION_LINKS, Document.class, new ArrayList<>());

        StringBuilder details = new StringBuilder();
        details.append("📊 **Configuration actuelle du message**\n")
                .append("• Nom du serveur: ").append(serverName).append("\n\n");

        // Liste des rôles
        details.append("**Rôles** (").append(roles.size()).append("):\n");
        for (Document role : roles) {
            details.append("- ").append(role.getString("emoji")).append(" **")
                    .append(role.getString("name")).append("**: ")
                    .append(role.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des catégories
        details.append("**Catégories** (").append(categories.size()).append("):\n");
        for (Document cat : categories) {
            details.append("- ").append(cat.getString("emoji")).append(" **")
                    .append(cat.getString("name")).append("**: ")
                    .append(cat.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des salons
        details.append("**Salons importants** (").append(channels.size()).append("):\n");
        for (Document chan : channels) {
            details.append("- ").append(chan.getString("emoji")).append(" **")
                    .append(chan.getString("name")).append("**: ")
                    .append(chan.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des liens
        details.append("**Liens utiles** (").append(links.size()).append("):\n");
        for (Document link : links) {
            details.append("- ").append(link.getString("emoji")).append(" **")
                    .append(link.getString("name")).append("**: ")
                    .append(link.getString("url")).append("\n");
        }

        e.getHook().sendMessage(details.toString()).queue();
    }

    /**
     * Button listener for resource management
     */
    private class ResourcesButtonListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(@NotNull net.dv8tion.jda.api.events.GenericEvent e) {
            if (e instanceof ButtonInteractionEvent event) {
                String buttonId = event.getComponentId();
                String userId = event.getUser().getId();
                if (buttonId.startsWith("confirm_delete:")) {
                    String[] parts = buttonId.split(":");
                    String sectionKey = parts[1];
                    String itemName = parts[2];
                    handleConfirmDelete(event, sectionKey, itemName);
                } else if (buttonId.startsWith("cancel_delete:")) {
                    event.getHook().sendMessage("Suppression annulée.")
                            .setEphemeral(true)
                            .queue(message -> {
                                message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                            });
                } else {
                    switch (buttonId) {
                        case BTN_ADD:
                            handleAddButtonClick(event, userId);
                            break;
                        case BTN_EDIT:
                            handleEditButtonClick(event, userId);
                            break;
                        case BTN_DELETE:
                            handleDeleteButtonClick(event, userId);
                            break;
                        case BTN_SERVER_NAME:
                            handleServerNameButtonClick(event);
                            break;
                        case BTN_BACK_TO_SECTIONS:
                            handleBackToSectionsButton(event);
                            break;
                    }
                }
            }
        }

        private void handleAddButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            // Si c'est rôles ou salons, on pourrait proposer une interface spéciale avec des sélecteurs
            if (sectionKey.equals(SECTION_ROLES) || sectionKey.equals(SECTION_CHANNELS)) {
                handleAddRoleOrChannel(e, sectionKey);
                return;
            }

            String sectionName = getSectionDisplayName(sectionKey);
            Modal.Builder modalBuilder = Modal.create(MODAL_ADD + ":" + sectionKey, "Ajouter un " + sectionName);

            // Ajouter le champ emoji pour les catégories et liens
            TextInput emojiInput = TextInput.create("emoji", "Emoji", TextInputStyle.SHORT)
                    .setPlaceholder("Cliquez ici puis utilisez Ctrl+Cmd+Space ou Win+. pour les emojis")
                    .setRequired(true)
                    .setMaxLength(5)
                    .build();
            modalBuilder.addComponents(ActionRow.of(emojiInput));

            // Champ nom pour tous
            TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT)
                    .setPlaceholder("Nom affiché dans le message")
                    .setRequired(true)
                    .setMaxLength(50)
                    .build();
            modalBuilder.addComponents(ActionRow.of(nameInput));

            // Champ spécifique selon la section
            if (sectionKey.equals(SECTION_LINKS)) {
                TextInput urlInput = TextInput.create("url", "URL", TextInputStyle.SHORT)
                        .setPlaceholder("Exemple: https://discord.gg/...")
                        .setRequired(true)
                        .build();
                modalBuilder.addComponents(ActionRow.of(urlInput));
            } else {
                TextInput descInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Description détaillée")
                        .setRequired(true)
                        .setMaxLength(200)
                        .build();
                modalBuilder.addComponents(ActionRow.of(descInput));
            }

            e.replyModal(modalBuilder.build()).queue();
        }

        /**
         * Gérer l'ajout de rôle ou salon avec sélecteur
         */
        private void handleAddRoleOrChannel(ButtonInteractionEvent e, String sectionKey) {
            if (sectionKey.equals(SECTION_ROLES)) {
                List<Role> roles = Objects.requireNonNull(e.getGuild()).getRoles();
                StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(SELECT_ROLE).setPlaceholder("Sélectionner un rôle").setMaxValues(1);
                for (Role role : roles) {
                    if (!role.isManaged() && !role.isPublicRole()) {
                        menuBuilder.addOption(role.getName(), role.getId(), "Rôle: " + role.getName());
                    }
                }
                e.reply("Choisissez un rôle à ajouter:").addComponents(ActionRow.of(menuBuilder.build())).setEphemeral(true).queue();
            } else if (sectionKey.equals(SECTION_CHANNELS)) {
                List<GuildChannel> channels = Objects.requireNonNull(e.getGuild()).getChannels();
                StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(SELECT_CHANNEL).setPlaceholder("Sélectionner un salon").setMaxValues(1);
                for (GuildChannel channel : channels) {
                    if (channel.getType().isMessage() || channel.getType().isThread() || channel.getType() == ChannelType.FORUM) {
                        String emoji = channel.getType().isThread() ? "🧵" : (channel.getType() == ChannelType.FORUM ? "📊" : "📝");
                        menuBuilder.addOption(emoji + " " + channel.getName(), channel.getId(), "Salon: " + channel.getName());
                    }
                }
                e.reply("Choisissez un salon à ajouter:").addComponents(ActionRow.of(menuBuilder.build())).setEphemeral(true).queue();
            }
        }

        private void handleEditButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            // Get items from the selected section
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                e.reply("❌ Configuration non trouvée. Veuillez d'abord exécuter /tr8 resources update.").setEphemeral(true).queue();
                return;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            if (items.isEmpty()) {
                e.reply("Aucun élément trouvé dans cette section.").setEphemeral(true).queue();
                return;
            }

            // Create a select menu with all items
            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("edit_item_select:" + sectionKey)
                    .setPlaceholder("Choisir l'élément à modifier");

            for (Document item : items) {
                String name = item.getString("name");
                // Gérer l'affichage de manière conditionnelle selon la section
                if (sectionKey.equals(SECTION_ROLES) || sectionKey.equals(SECTION_CHANNELS)) {
                    // Pour les rôles et les salons (pas d'emoji)
                    menuBuilder.addOption(name, name);
                } else {
                    // Pour les autres sections (avec emoji)
                    String emoji = item.getString("emoji");
                    menuBuilder.addOption(emoji + " " + name, name);
                }
            }

            e.reply("Choisissez l'élément à modifier :").addComponents(ActionRow.of(menuBuilder.build()))
                    .setEphemeral(true).queue();
        }

        private void handleDeleteButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            // Get items from the selected section
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                e.reply("❌ Configuration non trouvée. Veuillez d'abord exécuter /tr8 resources update.").setEphemeral(true).queue();
                return;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            if (items.isEmpty()) {
                e.reply("Aucun élément trouvé dans cette section.").setEphemeral(true).queue();
                return;
            }

            // Create a select menu with all items
            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("delete_item_select:" + sectionKey)
                    .setPlaceholder("Choisir l'élément à supprimer");

            for (Document item : items) {
                String name = item.getString("name");
                // Gérer l'affichage de manière conditionnelle selon la section
                if (sectionKey.equals(SECTION_ROLES) || sectionKey.equals(SECTION_CHANNELS)) {
                    // Pour les rôles et les salons (pas d'emoji)
                    menuBuilder.addOption(name, name);
                } else {
                    // Pour les autres sections (avec emoji)
                    String emoji = item.getString("emoji");
                    menuBuilder.addOption(emoji + " " + name, name);
                }
            }

            e.reply("⚠️ Choisissez l'élément à supprimer :").addComponents(ActionRow.of(menuBuilder.build()))
                    .setEphemeral(true).queue();
        }

        private void handleConfirmDelete(ButtonInteractionEvent e, String sectionKey, String itemName) {
            e.deferReply(true).queue();
            removeItemFromSection(sectionKey, itemName)
                    .thenAccept(v -> e.getHook().sendMessage("✅ L'élément **" + itemName + "** a été supprimé avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la suppression : " + error.getMessage()).queue();
                        return null;
                    });
        }

        private void handleServerNameButtonClick(ButtonInteractionEvent e) {
            TextInput nameInput = TextInput.create("server_name", "Nom du serveur", TextInputStyle.SHORT)
                    .setPlaceholder("Exemple: TheRockQC")
                    .setRequired(true)
                    .setMaxLength(50)
                    .build();

            Modal modal = Modal.create(MODAL_SERVER_NAME, "Modifier le nom du serveur")
                    .addComponents(ActionRow.of(nameInput))
                    .build();

            e.replyModal(modal).queue();
        }

        private void handleBackToSectionsButton(ButtonInteractionEvent e) {
            // Réinitialiser le choix de section de l'utilisateur
            String userId = e.getUser().getId();
            userSectionChoices.remove(userId);

            // Recréer le menu de sélection de section
            StringSelectMenu sectionSelect = StringSelectMenu.create(SELECT_SECTION)
                    .setPlaceholder("Choisir la section à modifier")
                    .addOption("🎭 Rôles", SECTION_ROLES, "Gérer les rôles affichés dans l'embed")
                    .addOption("📂 Catégories", SECTION_CATEGORIES, "Gérer les catégories affichées dans l'embed")
                    .addOption("📌 Salons importants", SECTION_CHANNELS, "Gérer les salons importants affichés dans l'embed")
                    .addOption("🔗 Liens utiles", SECTION_LINKS, "Gérer les liens utiles affichés dans l'embed")
                    .build();

            // Désactiver les boutons d'action
            Button addButton = Button.primary(BTN_ADD, "Ajouter").asDisabled();
            Button editButton = Button.secondary(BTN_EDIT, "Modifier").asDisabled();
            Button deleteButton = Button.danger(BTN_DELETE, "Supprimer").asDisabled();
            Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

            e.editComponents(
                            ActionRow.of(sectionSelect),
                            ActionRow.of(addButton, editButton, deleteButton),
                            ActionRow.of(serverNameButton)
                    ).setContent("👋 **Interface de gestion des ressources**\n" +
                            "Sélectionnez d'abord une section, puis choisissez l'action à effectuer.")
                    .queue();
        }
    }

    /**
     * Select menu listener for resource management
     */
    private class ResourcesSelectMenuListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(@NotNull net.dv8tion.jda.api.events.GenericEvent e) {
            if (e instanceof StringSelectInteractionEvent event) {
                String menuId = event.getComponentId();
                if (menuId.equals(SELECT_SECTION)) {
                    handleSectionSelect(event);
                } else if (menuId.startsWith("edit_item_select:")) {
                    handleEditItemSelect(event);
                } else if (menuId.startsWith("delete_item_select:")) {
                    handleDeleteItemSelect(event);
                } else if (menuId.equals(SELECT_CHANNEL)) {
                    handleChannelSelect(event);
                } else if (menuId.equals(SELECT_ROLE)) {
                    handleRoleSelect(event);
                }
            }
        }

        private void handleSectionSelect(StringSelectInteractionEvent e) {
            String selectedSection = e.getValues().getFirst();
            String userId = e.getUser().getId();

            // Store the user's selection
            userSectionChoices.put(userId, selectedSection);

            // Enable action buttons
            Button addButton = Button.primary(BTN_ADD, "Ajouter");
            Button editButton = Button.secondary(BTN_EDIT, "Modifier");
            Button deleteButton = Button.danger(BTN_DELETE, "Supprimer");
            Button backButton = Button.secondary("resources_back_to_sections", "← Retour aux sections");
            Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

            e.editComponents(
                            ActionRow.of(addButton, editButton, deleteButton),
                            ActionRow.of(backButton),
                            ActionRow.of(serverNameButton)
                    ).setContent("👋 **Interface de gestion des ressources**\n" +
                            "Section sélectionnée: **" + getSectionDisplayName(selectedSection) + "**\n" +
                            "Choisissez maintenant l'action à effectuer.")
                    .queue();
        }

        private void handleEditItemSelect(StringSelectInteractionEvent e) {
            String[] parts = e.getComponentId().split(":");
            String sectionKey = parts[1];
            String itemName = e.getValues().getFirst();

            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());

            Document itemToEdit = null;
            for (Document item : items) {
                if (item.getString("name").equals(itemName)) {
                    itemToEdit = item;
                    break;
                }
            }
            if (itemToEdit == null) {
                e.reply("❌ Élément non trouvé.").setEphemeral(true).queue();
                return;
            }

            Modal.Builder modalBuilder = Modal.create(MODAL_EDIT + ":" + sectionKey + ":" + itemName, "Modifier " + getSectionItemName(sectionKey) + ": " + itemName);
            switch (sectionKey) {
                case SECTION_ROLES:
                case SECTION_CHANNELS: {
                    String paramName = itemToEdit.getString("name");
                    String paramId = itemToEdit.getString("id");
                    TextInput idInput = TextInput.create("id", "ID (ne pas modifier)", TextInputStyle.SHORT).setValue(paramId).setRequired(true).build();
                    TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT).setValue(paramName).setRequired(true).setMaxLength(50).build();
                    TextInput descInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH).setValue(itemToEdit.getString("description")).setRequired(true).setMaxLength(200).build();
                    modalBuilder.addComponents(ActionRow.of(nameInput), ActionRow.of(descInput), ActionRow.of(idInput));
                    break;
                }
                case SECTION_LINKS: {
                    TextInput emojiInput = TextInput.create("emoji", "Emoji", TextInputStyle.SHORT).setValue(itemToEdit.getString("emoji")).setRequired(true).setMaxLength(5).build();
                    TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT).setValue(itemToEdit.getString("name")).setRequired(true).setMaxLength(50).build();
                    TextInput urlInput = TextInput.create("url", "URL", TextInputStyle.SHORT).setValue(itemToEdit.getString("url")).setRequired(true).build();
                    modalBuilder.addComponents(ActionRow.of(emojiInput), ActionRow.of(nameInput), ActionRow.of(urlInput));
                    break;
                }
                default: {
                    TextInput emojiInput = TextInput.create("emoji", "Emoji", TextInputStyle.SHORT).setValue(itemToEdit.getString("emoji")).setRequired(true).setMaxLength(5).build();
                    TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT).setValue(itemToEdit.getString("name")).setRequired(true).setMaxLength(50).build();
                    TextInput descInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH).setValue(itemToEdit.getString("description")).setRequired(true).setMaxLength(200).build();
                    modalBuilder.addComponents(ActionRow.of(emojiInput), ActionRow.of(nameInput), ActionRow.of(descInput));
                    break;
                }
            }

            e.replyModal(modalBuilder.build()).queue();
        }


        private void handleDeleteItemSelect(StringSelectInteractionEvent e) {
            String[] parts = e.getComponentId().split(":");
            String sectionKey = parts[1];
            String itemName = e.getValues().getFirst();
            Button confirmButton = Button.danger("confirm_delete:" + sectionKey + ":" + itemName, "Confirmer");
            Button cancelButton = Button.secondary("cancel_delete:" + sectionKey + ":" + itemName, "Annuler");
            e.reply("⚠️ Êtes-vous sûr de vouloir supprimer **" + itemName + "** ?")
                    .addComponents(ActionRow.of(confirmButton, cancelButton))
                    .setEphemeral(true)
                    .queue();
        }

        /**
         * Gestion de la sélection d'un salon
         */
        private void handleChannelSelect(StringSelectInteractionEvent e) {
            String channelId = e.getValues().getFirst();
            GuildChannel channel = Objects.requireNonNull(e.getGuild()).getGuildChannelById(channelId);

            if (channel == null) {
                e.reply("❌ Salon introuvable.").setEphemeral(true).queue();
                return;
            }

            // Créer un modal pour ajouter la description du salon
            Modal.Builder modalBuilder = Modal.create(
                    MODAL_ADD_WITH_SELECTOR + ":" + SECTION_CHANNELS + ":" + channelId,
                    "Ajouter le salon: " + channel.getName()
            );

            TextInput descInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
                    .setPlaceholder("Description détaillée de ce salon")
                    .setRequired(true)
                    .setMaxLength(200)
                    .build();

            modalBuilder.addComponents(ActionRow.of(descInput));
            e.replyModal(modalBuilder.build()).queue();
        }

        /**
         * Gestion de la sélection d'un rôle
         */
        private void handleRoleSelect(StringSelectInteractionEvent e) {
            String roleId = e.getValues().getFirst();
            Role role = Objects.requireNonNull(e.getGuild()).getRoleById(roleId);

            if (role == null) {
                e.reply("❌ Rôle introuvable.").setEphemeral(true).queue();
                return;
            }

            // Créer un modal pour ajouter la description du rôle
            Modal.Builder modalBuilder = Modal.create(
                    MODAL_ADD_WITH_SELECTOR + ":" + SECTION_ROLES + ":" + roleId,
                    "Ajouter le rôle: " + role.getName()
            );

            TextInput descInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
                    .setPlaceholder("Description détaillée de ce rôle")
                    .setRequired(true)
                    .setMaxLength(200)
                    .build();

            modalBuilder.addComponents(ActionRow.of(descInput));
            e.replyModal(modalBuilder.build()).queue();
        }
    }

    /**
     * Modal listener for resource management
     */
    private class ResourcesModalListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(@NotNull net.dv8tion.jda.api.events.GenericEvent e) {
            if (e instanceof ModalInteractionEvent event) {
                String modalId = event.getModalId();
                try {
                    if (modalId.startsWith(MODAL_ADD)) {
                        handleAddModal(event);
                    } else if (modalId.startsWith(MODAL_EDIT)) {
                        handleEditModal(event);
                    } else if (modalId.startsWith(MODAL_DELETE)) {
                        handleDeleteModal(event);
                    } else if (modalId.equals(MODAL_SERVER_NAME)) {
                        handleServerNameModal(event);
                    } else if (modalId.startsWith(MODAL_ADD_WITH_SELECTOR)) {
                        handleAddWithSelectorModal(event);
                    }
                } catch (Exception ex) {
                    LOGGER.error("Erreur lors du traitement du modal: {}", ex.getMessage(), ex);
                    event.getHook().sendMessage("❌ Une erreur est survenue: " + ex.getMessage()).queue();
                }
            }
        }

        private void handleAddModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            Document newItem = new Document();

            // Obtenir le nom et la description pour tous les types
            String name = Objects.requireNonNull(e.getValue("name")).getAsString();
            newItem.append("name", name);

            // Ajouter l'emoji seulement pour les sections qui en ont besoin
            if (sectionKey.equals(SECTION_CATEGORIES) || sectionKey.equals(SECTION_LINKS)) {
                String emoji = Objects.requireNonNull(e.getValue("emoji")).getAsString();
                newItem.append("emoji", emoji);
            }

            if (sectionKey.equals(SECTION_LINKS)) {
                String url = Objects.requireNonNull(e.getValue("url")).getAsString();
                newItem.append("url", url);
            } else if (!sectionKey.equals(SECTION_ROLES) && !sectionKey.equals(SECTION_CHANNELS)) {
                String description = Objects.requireNonNull(e.getValue("description")).getAsString();
                newItem.append("description", description);
            } else {
                String description = Objects.requireNonNull(e.getValue("description")).getAsString();
                newItem.append("description", description);
            }

            updateSectionWithNewItem(sectionKey, newItem, name)
                    .thenAccept(v -> e.getHook().sendMessage("✅ Élément **" + name + "** ajouté avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de l'ajout : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }

        private void handleEditModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String oldName = parts[2];
            Document updatedItem = new Document();
            String name = Objects.requireNonNull(e.getValue("name")).getAsString();
            updatedItem.append("name", name);

            if (sectionKey.equals(SECTION_CATEGORIES) || sectionKey.equals(SECTION_LINKS)) {
                String emoji = Objects.requireNonNull(e.getValue("emoji")).getAsString();
                updatedItem.append("emoji", emoji);
            }

            if (sectionKey.equals(SECTION_LINKS)) {
                String url = Objects.requireNonNull(e.getValue("url")).getAsString();
                updatedItem.append("url", url);
            } else if (sectionKey.equals(SECTION_CHANNELS) || sectionKey.equals(SECTION_ROLES)) {
                String id = Objects.requireNonNull(e.getValue("id")).getAsString();
                String description = Objects.requireNonNull(e.getValue("description")).getAsString();
                updatedItem.append("id", id).append("description", description);
            } else {
                String description = Objects.requireNonNull(e.getValue("description")).getAsString();
                updatedItem.append("description", description);
            }

            updateSectionWithNewItem(sectionKey, updatedItem, name, oldName)
                    .thenAccept(v -> e.getHook().sendMessage("✅ Élément **" + oldName + "** modifié avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la modification : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }

        private void handleDeleteModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String itemName = parts[2];
            String confirmation = Objects.requireNonNull(e.getValue("confirm")).getAsString();
            if (confirmation.equals("CONFIRMER")) {
                removeItemFromSection(sectionKey, itemName)
                        .thenAccept(v -> e.getHook().sendMessage("✅ L'élément **" + itemName + "** a été supprimé avec succès.").queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        }))
                        .exceptionally(error -> {
                            e.getHook().sendMessage("❌ Erreur lors de la suppression : " + error.getMessage()).queue(message -> {
                                message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                            });
                            return null;
                        });
            } else {
                e.getHook().sendMessage("⚠️ Suppression annulée. Le texte de confirmation était incorrect.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                });
            }
        }

        private void handleServerNameModal(ModalInteractionEvent e) {
            String serverName = Objects.requireNonNull(e.getValue("server_name")).getAsString();
            ResourcesMessageHandler.updateServerName(serverName)
                    .thenAccept(v -> e.getHook().sendMessage("✅ Le nom du serveur a été mis à jour en **" + serverName + "**.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }

        /**
         * Gestion du modal après sélection de salon/rôle
         */
        private void handleAddWithSelectorModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String entityId = parts[2];
            String description = Objects.requireNonNull(e.getValue("description")).getAsString();
            Document newItem = new Document();
            if (sectionKey.equals(SECTION_ROLES)) {
                Role role = Objects.requireNonNull(e.getGuild()).getRoleById(entityId);
                if (role == null) {
                    e.getHook().sendMessage("❌ Rôle introuvable.").queue();
                    return;
                }
                newItem.append("name", role.getName()).append("id", role.getId()).append("description", description);
            } else if (sectionKey.equals(SECTION_CHANNELS)) {
                GuildChannel channel = Objects.requireNonNull(e.getGuild()).getGuildChannelById(entityId);
                if (channel == null) {
                    e.getHook().sendMessage("❌ Salon introuvable.").queue();
                    return;
                }
                newItem.append("name", channel.getName()).append("id", channel.getId()).append("description", description);
            }
            updateSectionWithNewItem(sectionKey, newItem, newItem.getString("name"))
                    .thenAccept(v -> e.getHook().sendMessage("✅ " + (sectionKey.equals(SECTION_ROLES) ? "Rôle" : "Salon") +
                            " **" + newItem.getString("name") + "** ajouté avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de l'ajout : " + error.getMessage()).queue();
                        return null;
                    });
        }
    }

    /**
     * Overloaded version for backwards compatibility
     */
    private CompletableFuture<Void> updateSectionWithNewItem(String sectionKey, Document newItem, String itemName) {
        return updateSectionWithNewItem(sectionKey, newItem, itemName, null);
    }

    /**
     * Updates a section with a new item, replacing it if it already exists
     * @param sectionKey The key of the section to update
     * @param newItem The new item to add or update
     * @param newItemName The name of the new item
     * @param oldItemName The original name of the item (if updating existing item)
     */
    private CompletableFuture<Void> updateSectionWithNewItem(String sectionKey, Document newItem, String newItemName, String oldItemName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                future.completeExceptionally(new IllegalStateException("Configuration non trouvée. Veuillez d'abord exécuter /tr8 resources update."));
                return future;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            boolean found = false;
            if (oldItemName != null && !oldItemName.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getString("name").equals(oldItemName)) {
                        items.set(i, newItem);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getString("name").equals(newItemName)) {
                        items.set(i, newItem);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) items.add(newItem);
            ResourcesMessageHandler.updateSection(sectionKey, items)
                    .thenAccept(v -> future.complete(null))
                    .exceptionally(error -> {
                        future.completeExceptionally(error);
                        return null;
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Removes an item from a section
     */
    private CompletableFuture<Void> removeItemFromSection(String sectionKey, String itemName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                future.completeExceptionally(new IllegalStateException("Configuration non trouvée. Veuillez d'abord exécuter /tr8 resources update."));
                return future;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            items.removeIf(item -> item.getString("name").equals(itemName));
            ResourcesMessageHandler.updateSection(sectionKey, items)
                    .thenAccept(v -> future.complete(null))
                    .exceptionally(error -> {
                        future.completeExceptionally(error);
                        return null;
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Helper method to get display name for a section
     */
    private String getSectionDisplayName(String sectionKey) {
        return switch (sectionKey) {
            case SECTION_ROLES -> "Rôles";
            case SECTION_CATEGORIES -> "Catégories";
            case SECTION_CHANNELS -> "Salons importants";
            case SECTION_LINKS -> "Liens utiles";
            default -> "Section";
        };
    }

    /**
     * Helper method to get item name for a section (singular form)
     */
    private String getSectionItemName(String sectionKey) {
        return switch (sectionKey) {
            case SECTION_ROLES -> "Rôle";
            case SECTION_CATEGORIES -> "Catégorie";
            case SECTION_CHANNELS -> "Salon";
            case SECTION_LINKS -> "Lien";
            default -> "Élément";
        };
    }
}
