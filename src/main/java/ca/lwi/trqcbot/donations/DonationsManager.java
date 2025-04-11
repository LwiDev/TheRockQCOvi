package ca.lwi.trqcbot.donations;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public class DonationsManager extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DonationsManager.class);

    private final String channelId;

    public DonationsManager() {
        Dotenv dotenv = Dotenv.load();
        this.channelId = dotenv.get("CHANNEL_HALL_OF_FAME_ID");
    }

    public void handleUpdateDonors(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();
        DonationsHandler.sendPermanentDonorsMessage(this.channelId, forceNew)
                .thenRun(() -> e.getHook().editOriginal("Le tableau des donateurs a été mis à jour avec succès!").queue())
                .exceptionally(error -> {
                    e.getHook().editOriginal("Erreur lors de la mise à jour du tableau des donateurs: " + error.getMessage()).queue();
                    return null;
                });
    }

    public void handleAddToDonor(SlashCommandInteractionEvent e) {
        List<String> donorNames = DonationsHandler.getDonorNamesList();
        if (donorNames.isEmpty()) {
            e.reply("Aucun donateur trouvé dans la base de données.").setEphemeral(true).queue();
            return;
        }
        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("donor-select").setPlaceholder("Sélectionner un donateur").setMaxValues(1);
        donorNames.sort(String::compareToIgnoreCase);
        List<String> limitedList = donorNames.size() > 25 ? donorNames.subList(0, 25) : donorNames;
        limitedList.forEach(name -> menuBuilder.addOption(name, name));
        e.reply("Sélectionne le donateur à qui ajouter un don:").addActionRow(menuBuilder.build()).setEphemeral(true).queue();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getComponentId().equals("donor-select")) {
            String selectedDonor = event.getValues().getFirst();
            TextInput amountInput = TextInput.create("amount", "Montant du don", TextInputStyle.SHORT)
                    .setPlaceholder("Entrez le montant (nombre entier)")
                    .setMinLength(1)
                    .setMaxLength(5)
                    .setRequired(true)
                    .build();
            Modal modal = Modal.create("donation-amount-modal-" + selectedDonor, "Ajouter un don pour " + selectedDonor).addActionRow(amountInput).build();
            event.replyModal(modal).queue();
        }
    }

    public void handleAddNewDonor(SlashCommandInteractionEvent event) {
        TextInput nameInput = TextInput.create("name", "Nom du donateur", TextInputStyle.SHORT)
                .setPlaceholder("Entrez le nom du donateur")
                .setMinLength(1)
                .setMaxLength(50)
                .setRequired(true)
                .build();

        TextInput amountInput = TextInput.create("amount", "Montant du don ($)", TextInputStyle.SHORT)
                .setPlaceholder("Entrez le montant (nombre entier)")
                .setMinLength(1)
                .setMaxLength(5)
                .setRequired(true)
                .build();

        Modal modal = Modal.create("new-donor-modal", "Ajouter un nouveau donateur")
                .addActionRow(nameInput)
                .addActionRow(amountInput)
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent e) {
        switch (e.getModalId()) {
            case "donation-amount-modal":
                handleDonationAmountModal(e);
                break;
            case "new-donor-modal":
                handleNewDonorModal(e);
                break;
        }
    }

    private void handleDonationAmountModal(ModalInteractionEvent e) {
        String amountStr = Objects.requireNonNull(e.getValue("amount")).getAsString();
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }

            // Récupérer le nom du donateur depuis le titre du modal
            String[] modalIdParts = e.getModalId().split("-");
            String donorName = e.getValues().getFirst().getId();
            if (e.getModalId().startsWith("donation-amount-modal-")) {
                donorName = e.getModalId().substring("donation-amount-modal-".length());
            } else {
                e.reply("Erreur: impossible d'identifier le donateur.").setEphemeral(true).queue();
                return;
            }

            boolean success = DonationsHandler.addDonationToExistingDonor(donorName, amount);
            if (success) {
                e.reply("Don de " + amount + "$ ajouté pour " + donorName + " avec succès!").setEphemeral(true).queue();
            } else {
                e.reply("Erreur lors de l'ajout du don. Vérifiez que le donateur existe.").setEphemeral(true).queue();
            }
        } catch (NumberFormatException ex) {
            e.reply("Le montant doit être un nombre entier valide.").setEphemeral(true).queue();
        }
    }

    private void handleNewDonorModal(ModalInteractionEvent e) {
        String donorName = Objects.requireNonNull(e.getValue("name")).getAsString();
        String amountStr = Objects.requireNonNull(e.getValue("amount")).getAsString();

        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }
            boolean success = DonationsHandler.addNewDonor(donorName, amount);

            if (success) {
                e.reply("Nouveau donateur " + donorName + " ajouté avec un don initial de " + amount + "$.").setEphemeral(true).queue();
            } else {
                e.reply("Erreur lors de l'ajout du nouveau donateur.").setEphemeral(true).queue();
            }
        } catch (NumberFormatException ex) {
            e.reply("Le montant doit être un nombre entier valide.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String componentId = e.getComponentId();
        if (componentId.equals("donors_view")) {
            DonationsHandler.handleViewButton(e);
            return;
        }
        if (componentId.startsWith("donors_")) {
            String[] parts = componentId.split("_");
            if (parts.length >= 3) {
                String action = parts[1];
                String userId = parts[2];
                if (userId.equals(e.getUser().getId())) {
                    DonationsHandler.handlePaginationButton(e, action);
                } else {
                    e.reply("Ces boutons sont destinés à un autre utilisateur.").setEphemeral(true).queue();
                }
            }
        }
    }
}
