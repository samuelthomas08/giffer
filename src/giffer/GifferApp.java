package giffer;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The application window: header with search, grid of GIF cards, detail panel, and the glue between the parts. */
@SuppressWarnings("deprecation") // StageStyle.EXTENDED and HeaderBar are preview APIs that JavaFX marks as deprecated
public final class GifferApp extends Application {
    /** The running instance, for tests and screenshots. */
    static GifferApp instance;

    private static final int CARDS_PER_BATCH = 90;
    private static final double HEADER_HEIGHT = 56;
    private static final int FILTER_CHIP_COUNT = 10;
    private static final String WHOLE_TEXT_IS_ONE_LINK = "(?is)^\\s*https?://\\S+\\s*$";

    // ---------------------------------------------------------------- model, services, workflows
    final GifLibrary library = new GifLibrary(libraryDirectory());
    Settings settings;
    final ExecutorService backgroundThreads = createThreadPool("giffer-background", 2);
    final ExecutorService analysisThreads = createThreadPool("giffer-analysis", 2);
    final ExecutorService thumbnailThreads = createThreadPool("giffer-thumbnails", 3);
    final AnimationCache animationCache = new AnimationCache(createThreadPool("giffer-animations", 2));
    final ActivityIndicator activity = new ActivityIndicator();
    final ToastLayer toasts = new ToastLayer();
    final ModalLayer modal = new ModalLayer();
    final ImportWorkflow importing = new ImportWorkflow(this);
    final AnalysisWorkflow analysis = new AnalysisWorkflow(this);
    final PasteWorkflow paste = new PasteWorkflow(this);
    final SuggestionWorkflow suggestions = new SuggestionWorkflow(this);
    final StringProperty pasteButtonLabel = paste.buttonLabel;

    // ---------------------------------------------------------------- state
    GifEntry selectedEntry;
    /** True while a card is being dragged out of the window, so the window does not treat it as an import. */
    boolean draggingFromGrid;
    private List<GifEntry> searchResults = List.of();
    private int shownCardCount;
    private final Map<String, GifCard> cardsByEntryId = new HashMap<>();
    private boolean usesExtendedWindowFrame;
    /** When true the grid shows only favourites (combined with whatever is typed in the search). */
    private boolean favoritesOnly;
    private String libraryLoadError;

    // ---------------------------------------------------------------- UI
    Stage stage;
    Scene scene;
    StackPane root;
    private TextField searchField;
    private final MasonryPane resultsGrid = new MasonryPane();
    private ScrollPane resultsScroll;
    private FilterChipRow filterChips;
    private Label countLabel;
    private Button analyzeAllButton;
    private Node emptyLibraryView;
    private Node noResultsView;
    private StackPane noResultsRing;
    private Label noResultsTitle;
    private Label noResultsText;
    private DetailPanel detailPanel;
    private StackPane dropOverlay;

    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(120));
    private final PauseTransition refreshDebounce = new PauseTransition(Duration.millis(90));
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(600));

    /**
     * Where the library lives: {@code -Dgiffer.home=...} if given, else {@code ~/Giffer}. A library from the time
     * before the project was renamed ({@code ~/GifVault}) is moved to the new place on first start.
     */
    static Path libraryDirectory() {
        String override = System.getProperty("giffer.home");
        if (override != null && !override.isBlank()) return Path.of(override);

        Path userHome = Path.of(System.getProperty("user.home"));
        Path libraryDirectory = userHome.resolve("Giffer");
        Path legacyLibraryDirectory = userHome.resolve("GifVault");
        if (!Files.exists(libraryDirectory) && Files.isDirectory(legacyLibraryDirectory)) {
            try {
                return Files.move(legacyLibraryDirectory, libraryDirectory);
            } catch (IOException cannotMove) {
                return legacyLibraryDirectory; // keep using it where it is
            }
        }
        return libraryDirectory;
    }

    private static ExecutorService createThreadPool(String threadName, int threadCount) {
        return Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    // ================================================================= startup

    @Override
    public void start(Stage primaryStage) {
        instance = this;
        stage = primaryStage;
        try {
            library.load();
        } catch (IOException loadFailure) {
            libraryLoadError = loadFailure.getMessage();
        }
        settings = Settings.load(library.settingsFile());
        paste.updateButtonLabel();

        try {
            stage.initStyle(StageStyle.EXTENDED);
            usesExtendedWindowFrame = true;
        } catch (RuntimeException | LinkageError previewApiUnavailable) {
            usesExtendedWindowFrame = false; // fall back to the regular window frame
        }

        BorderPane layout = new BorderPane();
        layout.setTop(buildHeader());
        layout.setCenter(buildBody());
        dropOverlay = buildDropOverlay();
        root = new StackPane(layout, activity, toasts, dropOverlay, modal);
        scene = new Scene(root, 1320, 840);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        applyTheme();
        Platform.getPreferences().colorSchemeProperty().addListener((_, _, _) -> {
            if (Settings.THEME_SYSTEM.equals(settings.themeMode)) applyTheme();
        });
        installInputHandlers();

        activity.setOnIdle(this::refreshResultStates);
        searchDebounce.setOnFinished(_ -> {
            shownCardCount = 0;
            resultsScroll.setVvalue(0);
            refreshResultsNow();
        });
        refreshDebounce.setOnFinished(_ -> refreshResultsNow());
        saveDebounce.setOnFinished(_ -> library.save());

        for (int iconSize : new int[]{16, 32, 64, 256}) stage.getIcons().add(Ui.appIcon(iconSize));
        stage.setTitle("Giffer");
        stage.setMinWidth(940);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.setOnCloseRequest(_ -> saveAll());
        stage.show();

        refreshResultsNow();
        searchField.requestFocus();
        if (libraryLoadError != null) toasts.show("Bibliothek konnte nicht geladen werden: " + libraryLoadError, ToastLayer.Kind.ERROR);

        // background chores: older entries without a size, GIFs still waiting for their analysis
        backgroundThreads.submit(() -> {
            if (library.fillMissingDimensions()) {
                library.save();
                Platform.runLater(resultsGrid::requestLayout);
            }
        });
        if (settings.hasGeminiApiKey() && settings.analyzeNewGifsAutomatically) analysis.analyzeAllPending();
    }

    @Override
    public void stop() {
        saveAll();
    }

    private void saveAll() {
        if (detailPanel != null) detailPanel.commitPendingEdits();
        try {
            library.save();
        } catch (RuntimeException ignored) {
            // nothing sensible left to do while shutting down
        }
    }

    void saveSettings() {
        try {
            settings.save(library.settingsFile());
        } catch (IOException saveFailure) {
            toasts.show("Einstellungen konnten nicht gespeichert werden: " + saveFailure.getMessage(), ToastLayer.Kind.ERROR);
        }
    }

    // ================================================================= header

    private Node buildHeader() {
        HBox leading = new HBox(10, Ui.logo(), Ui.label("Giffer", "brand"));
        leading.setAlignment(Pos.CENTER_LEFT);
        leading.setPadding(new Insets(0, 12, 0, 18));

        Node searchBox = buildSearchBox();

        Button suggestButton = Ui.button("GIF vorschlagen", "sparkle", "outline", () -> SuggestionDialog.show(this));
        Tooltip suggestTooltip = new Tooltip("Nachricht eingeben, Gemini sucht das passende GIF (Strg+G)");
        suggestTooltip.setShowDelay(Duration.millis(400));
        suggestButton.setTooltip(suggestTooltip);
        Button addButton = Ui.button("Hinzufügen", "add", "primary", () -> ImportDialog.show(this));
        Button settingsButton = Ui.iconButton("settings", "Einstellungen", () -> SettingsDialog.show(this, SettingsDialog.TAB_GEMINI));
        HBox trailing = new HBox(6, suggestButton, addButton, settingsButton);
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setPadding(new Insets(0, 10, 0, 12));

        if (usesExtendedWindowFrame) {
            HeaderBar headerBar = new HeaderBar();
            headerBar.setLeading(leading);
            headerBar.setCenter(searchBox);
            headerBar.setTrailing(trailing);
            headerBar.getStyleClass().add("header");
            headerBar.setMinHeight(HEADER_HEIGHT);
            headerBar.setPrefHeight(HEADER_HEIGHT);
            HeaderBar.setAlignment(searchBox, Pos.CENTER);
            HeaderBar.setDragType(leading, HeaderDragType.DRAGGABLE_SUBTREE);
            HeaderBar.setPrefButtonHeight(stage, HEADER_HEIGHT);
            return headerBar;
        }
        BorderPane headerBar = new BorderPane();
        headerBar.setLeft(leading);
        headerBar.setCenter(searchBox);
        headerBar.setRight(trailing);
        BorderPane.setAlignment(searchBox, Pos.CENTER);
        BorderPane.setAlignment(leading, Pos.CENTER_LEFT);
        headerBar.getStyleClass().add("header");
        headerBar.setMinHeight(HEADER_HEIGHT);
        headerBar.setPrefHeight(HEADER_HEIGHT);
        return headerBar;
    }

    private Node buildSearchBox() {
        searchField = new TextField();
        searchField.setPromptText("Stichwort, Emotion oder Situation suchen …");
        searchField.getStyleClass().add("search-field");

        Button clearButton = Ui.iconButton("close", "Suche leeren", () -> {
            searchField.clear();
            searchField.requestFocus();
        });
        clearButton.getStyleClass().add("mini");
        clearButton.visibleProperty().bind(searchField.textProperty().isNotEmpty());
        clearButton.managedProperty().bind(clearButton.visibleProperty());

        Label shortcutHint = Ui.label("Strg F", "kbd");
        shortcutHint.visibleProperty().bind(searchField.textProperty().isEmpty().and(searchField.focusedProperty().not()));
        shortcutHint.managedProperty().bind(shortcutHint.visibleProperty());

        HBox searchBox = new HBox(8, Ui.icon("search", 16), searchField, shortcutHint, clearButton);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("search-box");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBox.setPrefWidth(460);
        searchBox.setMaxWidth(520);
        searchBox.setMaxHeight(36); // the header would otherwise stretch it to its full height
        searchField.focusedProperty().addListener((_, _, isFocused) -> searchBox.pseudoClassStateChanged(Ui.FOCUS_WITHIN, isFocused));

        searchField.textProperty().addListener((_, _, newText) -> {
            if (newText.matches(WHOLE_TEXT_IS_ONE_LINK)) {
                // a link pasted into the search box means "add this GIF"
                String link = newText.strip();
                Platform.runLater(() -> {
                    searchField.clear();
                    importing.importLinks(List.of(link));
                });
                return;
            }
            searchDebounce.playFromStart();
        });
        searchField.setOnAction(_ -> {
            if (!searchResults.isEmpty()) paste.copyToClipboard(searchResults.get(0)); // Enter takes the best match
        });
        return searchBox;
    }

    // ================================================================= body

    private Node buildBody() {
        filterChips = new FilterChipRow();
        countLabel = Ui.label("", "subtle");
        analyzeAllButton = Ui.button("", "sparkle", "ghost small", analysis::analyzeAllPendingOnUserRequest);
        analyzeAllButton.setVisible(false);
        analyzeAllButton.setManaged(false);
        HBox summaryOnTheRight = new HBox(10, analyzeAllButton, countLabel);
        summaryOnTheRight.setAlignment(Pos.CENTER_RIGHT);
        BorderPane toolbar = new BorderPane();
        toolbar.setPadding(new Insets(16, 24, 4, 24));
        toolbar.setCenter(filterChips);
        toolbar.setRight(summaryOnTheRight);
        BorderPane.setAlignment(summaryOnTheRight, Pos.CENTER_RIGHT);
        BorderPane.setMargin(summaryOnTheRight, new Insets(0, 0, 0, 16));

        resultsGrid.setPadding(new Insets(12, 24, 34, 24));
        resultsGrid.setOnMouseClicked(event -> {
            if (event.getTarget() == resultsGrid) selectEntry(null); // click on the empty background
        });
        resultsScroll = new ScrollPane(resultsGrid);
        resultsScroll.setFitToWidth(true);
        resultsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resultsScroll.getStyleClass().add("grid-scroll");
        resultsScroll.vvalueProperty().addListener((_, _, scrollPosition) -> {
            boolean nearTheEnd = scrollPosition.doubleValue() > 0.8;
            if (nearTheEnd && shownCardCount < searchResults.size()) showCards(shownCardCount + CARDS_PER_BATCH);
        });

        emptyLibraryView = buildEmptyLibraryView();
        noResultsView = buildNoResultsView();
        StackPane resultsArea = new StackPane(resultsScroll, emptyLibraryView, noResultsView);
        VBox mainColumn = new VBox(toolbar, resultsArea);
        VBox.setVgrow(resultsArea, Priority.ALWAYS);

        detailPanel = new DetailPanel(this);
        detailPanel.setVisible(false);
        detailPanel.setManaged(false);

        BorderPane body = new BorderPane(mainColumn);
        body.setRight(detailPanel);
        return body;
    }

    private Node buildEmptyLibraryView() {
        Label title = Ui.label("Deine GIF-Sammlung ist noch leer", "h1");
        Label explanation = Ui.label("Füge Links von Tenor, Giphy & Co. ein oder ziehe GIF-Dateien einfach ins Fenster. "
                + "Gemini beschriftet jedes GIF automatisch, damit du es später per Stichwort wiederfindest.", "muted");
        explanation.setWrapText(true);
        explanation.setMaxWidth(440);
        explanation.setAlignment(Pos.CENTER);
        explanation.setTextAlignment(TextAlignment.CENTER);
        Button addButton = Ui.button("GIFs hinzufügen", "add", "primary", () -> ImportDialog.show(this));
        Label tip = Ui.label("Tipp: Link kopieren und Strg+V drücken", "subtle small");
        VBox view = new VBox(14, createIconRing("play"), title, explanation, addButton, tip);
        VBox.setMargin(addButton, new Insets(4, 0, 0, 0));
        view.setAlignment(Pos.CENTER);
        view.setVisible(false);
        return view;
    }

    private Node buildNoResultsView() {
        noResultsTitle = Ui.label("Nichts gefunden", "h1");
        noResultsText = Ui.label("", "muted");
        noResultsText.setWrapText(true);
        noResultsText.setMaxWidth(440);
        noResultsText.setTextAlignment(TextAlignment.CENTER);
        noResultsRing = createIconRing("search");
        VBox view = new VBox(14, noResultsRing, noResultsTitle, noResultsText);
        view.setAlignment(Pos.CENTER);
        view.setVisible(false);
        return view;
    }

    /** The rounded square with an icon above the headline of the empty-state views. */
    private static StackPane createIconRing(String iconName) {
        StackPane ring = new StackPane(Ui.icon(iconName, 24));
        ring.getStyleClass().add("empty-ring");
        ring.setMaxSize(56, 56);
        return ring;
    }

    private StackPane buildDropOverlay() {
        StackPane iconHolder = new StackPane(Ui.icon("upload", 32));
        iconHolder.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox content = new VBox(12, iconHolder, Ui.label("Zum Hinzufügen loslassen", "drop-text"));
        content.setAlignment(Pos.CENTER);
        StackPane overlay = new StackPane(content);
        overlay.getStyleClass().add("drop-overlay");
        overlay.setMouseTransparent(true);
        overlay.setVisible(false);
        return overlay;
    }

    // ================================================================= results grid

    /** Searches again and rebuilds the visible cards; keeps the scroll position and already loaded cards. */
    void refreshResultsNow() {
        searchResults = library.search(searchField.getText(), favoritesOnly);
        cardsByEntryId.keySet().retainAll(library.allIds());
        showCards(Math.max(shownCardCount, CARDS_PER_BATCH));
        updateFilterChips();
        refreshResultStates();
    }

    void refreshResultsSoon() {
        refreshDebounce.playFromStart();
    }

    private void showCards(int wantedCount) {
        int count = Math.min(wantedCount, searchResults.size());
        List<Node> cards = new ArrayList<>(count);
        for (int index = 0; index < count; index++) cards.add(cardFor(searchResults.get(index)));
        resultsGrid.getChildren().setAll(cards);
        shownCardCount = count;
    }

    private GifCard cardFor(GifEntry entry) {
        GifCard card = cardsByEntryId.computeIfAbsent(entry.id, _ -> new GifCard(this, entry));
        card.loadThumbnail();
        return card;
    }

    /** Chooses between grid, empty-library and no-results view; updates the count and the "analyse all" button. */
    void refreshResultStates() {
        boolean libraryIsEmpty = library.size() == 0;
        boolean nothingMatches = !libraryIsEmpty && searchResults.isEmpty();
        emptyLibraryView.setVisible(libraryIsEmpty);
        noResultsView.setVisible(nothingMatches);
        resultsScroll.setVisible(!libraryIsEmpty && !nothingMatches);

        String query = searchField.getText().strip();
        if (nothingMatches) {
            boolean noFavoritesYet = favoritesOnly && query.isEmpty();
            noResultsRing.getChildren().setAll(Ui.icon(noFavoritesYet ? "star" : "search", 24));
            noResultsTitle.setText(noFavoritesYet ? "Noch keine Favoriten" : "Nichts gefunden");
            if (noFavoritesYet) {
                noResultsText.setText("Markiere GIFs mit dem Stern, dann findest du sie hier wieder.");
            } else if (favoritesOnly) {
                noResultsText.setText("Unter deinen Favoriten passt kein GIF zu „" + query + "“. Schalte den Filter "
                        + "„Favoriten“ aus, um alle GIFs zu durchsuchen.");
            } else {
                noResultsText.setText("Zu „" + query + "“ passt kein GIF. Versuch ein verwandtes Wort, eine Emotion "
                        + "(z. B. „Freude“) oder beschreibe die Situation.");
            }
        }
        if (libraryIsEmpty) {
            countLabel.setText("");
        } else if (!query.isEmpty()) {
            countLabel.setText(searchResults.size() + " Treffer");
        } else if (favoritesOnly) {
            countLabel.setText(searchResults.size() + (searchResults.size() == 1 ? " Favorit" : " Favoriten"));
        } else {
            countLabel.setText(library.size() + (library.size() == 1 ? " GIF" : " GIFs"));
        }

        int pendingAnalysisCount = settings != null && settings.hasGeminiApiKey() ? library.entriesNeedingAnalysis().size() : 0;
        analyzeAllButton.setText(pendingAnalysisCount + " unanalysierte analysieren");
        analyzeAllButton.setVisible(pendingAnalysisCount > 0 && !analysis.isBusy());
        analyzeAllButton.setManaged(analyzeAllButton.isVisible());
    }

    private void updateFilterChips() {
        filterChips.getChildren().clear();
        if (library.size() == 0) return;
        String currentQuery = GifLibrary.normalizeForSearch(searchField.getText());
        filterChips.getChildren().add(createFilterChip("Alle", currentQuery.isEmpty() && !favoritesOnly, this::showAllGifs));

        Label favoritesChip = createFilterChip("Favoriten", favoritesOnly, () -> setFavoritesOnly(!favoritesOnly));
        Pane starIcon = Ui.icon("star", 12);
        Ui.setStarFilled(starIcon, favoritesOnly);
        favoritesChip.setGraphic(starIcon);
        favoritesChip.setGraphicTextGap(6);
        filterChips.getChildren().add(favoritesChip);

        for (String emotion : library.mostUsedEmotions(FILTER_CHIP_COUNT)) {
            boolean isActive = GifLibrary.normalizeForSearch(emotion).equals(currentQuery);
            filterChips.getChildren().add(createFilterChip(emotion, isActive, () -> searchField.setText(isActive ? "" : emotion)));
        }
    }

    private static Label createFilterChip(String text, boolean isActive, Runnable onClick) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chip");
        if (isActive) chip.getStyleClass().add("active");
        chip.setOnMouseClicked(_ -> onClick.run());
        return chip;
    }

    /** The "Alle" chip: no search text and no favourites filter. */
    private void showAllGifs() {
        boolean hadSearchText = !searchField.getText().isEmpty();
        searchField.clear(); // a changed text refreshes the results by itself
        if (favoritesOnly || !hadSearchText) setFavoritesOnly(false);
    }

    private void setFavoritesOnly(boolean enabled) {
        favoritesOnly = enabled;
        shownCardCount = 0;
        resultsScroll.setVvalue(0);
        refreshResultsNow();
    }

    /** Stars or un-stars a GIF; with the favourites filter on, the card disappears right away. */
    void toggleFavorite(GifEntry entry) {
        entry.favorite = !entry.favorite;
        GifCard card = cardsByEntryId.get(entry.id);
        if (card != null) card.refresh();
        if (selectedEntry == entry) detailPanel.refreshFavorite(entry);
        saveDebounce.playFromStart();
        if (favoritesOnly) refreshResultsSoon();
    }

    /** One line of chips; whatever does not fit is hidden (the first chip, "Alle", always stays). */
    private static final class FilterChipRow extends HBox {
        FilterChipRow() {
            super(8);
            setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void layoutChildren() {
            double availableWidth = getWidth() - snappedLeftInset() - snappedRightInset();
            double usedWidth = 0;
            boolean hideRemaining = false;
            for (Node chip : getChildren()) {
                double chipWidth = chip.prefWidth(-1);
                if (!hideRemaining && usedWidth > 0 && usedWidth + chipWidth > availableWidth) hideRemaining = true;
                if (chip.isManaged() == hideRemaining) {
                    chip.setManaged(!hideRemaining);
                    chip.setVisible(!hideRemaining);
                }
                if (!hideRemaining) usedWidth += chipWidth + getSpacing();
            }
            super.layoutChildren();
        }

        @Override
        protected double computePrefWidth(double height) {
            return 0; // take whatever the parent offers instead of pushing it wider
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }
    }

    // ================================================================= selection and entry actions

    /** Selects the entry and shows it in the detail panel; {@code null} closes the panel. */
    void selectEntry(GifEntry entry) {
        detailPanel.commitPendingEdits();
        selectedEntry = entry;
        for (GifCard card : cardsByEntryId.values()) card.setSelected(card.entry == entry);
        if (entry == null) {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
            detailPanel.clear();
            return;
        }
        boolean panelWasHidden = !detailPanel.isVisible();
        detailPanel.show(entry);
        detailPanel.setManaged(true);
        detailPanel.setVisible(true);
        if (panelWasHidden) {
            detailPanel.setOpacity(0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(160), detailPanel);
            fadeIn.setToValue(1);
            fadeIn.play();
        }
    }

    /** Clears the search so that the entry is visible, then selects it. */
    void showEntryInResults(GifEntry entry) {
        if (!searchField.getText().isEmpty()) searchField.clear();
        refreshResultsNow();
        selectEntry(entry);
    }

    void copyToClipboard(GifEntry entry) {
        paste.copyToClipboard(entry);
    }

    void pasteIntoApplication(GifEntry entry, boolean chooseApplication) {
        paste.pasteIntoApplication(entry, chooseApplication);
    }

    void revealInExplorer(GifEntry entry) {
        try {
            new ProcessBuilder("explorer.exe", "/select," + library.gifFile(entry).getAbsolutePath()).start();
        } catch (IOException startFailure) {
            toasts.show("Explorer konnte nicht geöffnet werden: " + startFailure.getMessage(), ToastLayer.Kind.ERROR);
        }
    }

    void openSourcePage(GifEntry entry) {
        if (!entry.sourceUrl.isBlank()) getHostServices().showDocument(entry.sourceUrl);
    }

    /** Called by the detail panel after every edit. */
    void onEntryEdited(GifEntry entry) {
        GifCard card = cardsByEntryId.get(entry.id);
        if (card != null) card.refresh();
        saveDebounce.playFromStart();
    }

    /** Called by the analysis whenever an entry starts, finishes or fails its analysis. */
    void onAnalysisStateChanged(GifEntry entry) {
        GifCard card = cardsByEntryId.get(entry.id);
        if (card != null) card.refresh();
        if (selectedEntry == entry) detailPanel.updateFields(entry);
    }

    void deleteEntry(GifEntry entry) {
        ConfirmDialog.show(this, "GIF löschen?", "„" + entry.displayName() + "“ wird endgültig aus der Bibliothek entfernt.",
                "Löschen", () -> {
                    if (selectedEntry == entry) selectEntry(null);
                    library.delete(entry);
                    cardsByEntryId.remove(entry.id);
                    refreshResultsNow();
                    toasts.show("GIF gelöscht", ToastLayer.Kind.INFO);
                });
    }

    // ================================================================= keyboard, clipboard, drag and drop

    private void installInputHandlers() {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            boolean typingInTextField = scene.getFocusOwner() instanceof TextInputControl;
            if (event.getCode() == KeyCode.V && event.isShortcutDown() && !typingInTextField) {
                importFromClipboard();
                event.consume();
            } else if (event.getCode() == KeyCode.F && event.isShortcutDown()) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (event.getCode() == KeyCode.G && event.isShortcutDown() && !modal.isShowing()) {
                SuggestionDialog.show(this);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                if (modal.isShowing()) modal.close();
                else if (!searchField.getText().isEmpty()) searchField.clear();
                else if (selectedEntry != null) selectEntry(null);
                else return;
                event.consume();
            }
        });

        scene.setOnDragOver(event -> {
            if (draggingFromGrid) return;
            Dragboard dragboard = event.getDragboard();
            if (dragboard.hasFiles() || dragboard.hasUrl() || dragboard.hasString()) {
                event.acceptTransferModes(TransferMode.COPY);
                dropOverlay.setVisible(true);
            }
            event.consume();
        });
        scene.setOnDragExited(_ -> dropOverlay.setVisible(false));
        scene.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean accepted = false;
            if (dragboard.hasFiles()) {
                importing.importFiles(dragboard.getFiles());
                accepted = true;
            } else {
                List<String> links = new ArrayList<>();
                if (dragboard.hasUrl()) links.addAll(LinkImporter.extractUrls(dragboard.getUrl()));
                if (links.isEmpty() && dragboard.hasString()) links.addAll(LinkImporter.extractUrls(dragboard.getString()));
                if (!links.isEmpty()) {
                    importing.importLinks(links);
                    accepted = true;
                }
            }
            event.setDropCompleted(accepted);
            dropOverlay.setVisible(false);
            event.consume();
        });
    }

    /** Ctrl+V outside of a text field: add whatever GIF files or links are on the clipboard. */
    private void importFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasFiles()) {
            importing.importFiles(clipboard.getFiles());
            return;
        }
        List<String> links = new ArrayList<>();
        if (clipboard.hasUrl()) links.addAll(LinkImporter.extractUrls(clipboard.getUrl()));
        if (links.isEmpty() && clipboard.hasString()) links.addAll(LinkImporter.extractUrls(clipboard.getString()));
        if (links.isEmpty()) toasts.show("In der Zwischenablage ist kein Link oder GIF.", ToastLayer.Kind.INFO);
        else importing.importLinks(links);
    }

    // ================================================================= theme

    void applyTheme() {
        ColorScheme colorScheme = switch (settings.themeMode) {
            case Settings.THEME_LIGHT -> ColorScheme.LIGHT;
            case Settings.THEME_DARK -> ColorScheme.DARK;
            default -> Platform.getPreferences().getColorScheme();
        };
        root.getStyleClass().removeAll("dark", "light");
        root.getStyleClass().add(colorScheme == ColorScheme.DARK ? "dark" : "light");
        scene.getPreferences().setColorScheme(colorScheme); // also colours the native window buttons
    }
}
