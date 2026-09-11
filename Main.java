//import javafx packages
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

//import SQL packages
import java.sql.*;

//System info imports
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;

public class Main extends Application {

    private TableView<Asset> table;
    private TableView<Employee> employeeTable;
    private TableView<Software> softwareTable;
    private ObservableList<Software> softwareData;
    private ComboBox<String> installedProgramsCombo;
    private ObservableList<Asset> data;
    private ObservableList<Employee> employeeData;
    private Stage primaryStage;

    // Track currently logged-in user for access control
    private int currentEmployeeId = -1;
    private boolean currentIsAdmin = false;
    private String currentDepartment = "";
    private String currentUserEmail = "";

    // Simple in-memory credential store using email + hashed password 
    // !!!MADE FOR DEMONSTRATION USE ONLY!!!!!!!!
    private static final Map<String, String> CREDENTIAL_STORE = Map.of(
            "DEMOUSER", hashPassword("user"),
            "DEMOADMIN", hashPassword("admin")
    );
    // !!!MADE FOR DEMONSTRATION USE ONLY!!!!!!!!

    // SYSTEM INFO HELPERS
    private String runPowerShell(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            // read all lines and join (some PS commands return multiple lines)
            String lines = reader.lines().collect(Collectors.joining("\n")).trim();
            return (lines.isEmpty()) ? "Unknown" : lines;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Helper: check if current user can access a given assetId.
     */
    private boolean canAccessAsset(int assetId) {
        if (currentIsAdmin) return true;
        if (currentEmployeeId <= 0) return false;

        String sql = "SELECT 1 FROM hardwareassets WHERE Id = ? AND EmployeeId = ?";
        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, assetId);
            ps.setInt(2, currentEmployeeId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void populateInstalledProgramsCombo() {
        try {
            // PowerShell
            String ps = "Get-ItemProperty HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\* | " +
                    "Select-Object DisplayName,DisplayVersion,Publisher | Where-Object { $_.DisplayName -ne $null } | " +
                    "ForEach-Object { \"$($_.DisplayName)|$($_.DisplayVersion)|$($_.Publisher)\" }";

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", ps);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            installedProgramsCombo.getItems().clear();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    installedProgramsCombo.getItems().add(line);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not populate installed programs: " + e.getMessage());
        }
    }
// Manufacturer of machine
    private String getManufacturer() {
        return runPowerShell("(Get-CimInstance Win32_ComputerSystem).Manufacturer");
    }

    private String getModel() {
        return runPowerShell("(Get-CimInstance Win32_ComputerSystem).Model");
    }

    private String getSystemName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getOSVendor() {
        String vendor = runPowerShell("(Get-CimInstance Win32_OperatingSystem).Manufacturer");
        if (vendor == null || vendor.isEmpty() || "Unknown".equals(vendor)) {
            String osName = System.getProperty("os.name", "Unknown").toLowerCase();
            if (osName.contains("windows")) return "Microsoft";
            if (osName.contains("linux")) return "Linux";
            if (osName.contains("mac") || osName.contains("darwin")) return "Apple";
            return "Unknown";
        }
        return vendor;
    }

    private String getSystemSpec(String key) {
        switch (key) {
            case "systemName":
                return getSystemName();
            case "manufacturer":
                return getManufacturer();
            case "model":
                return getModel();
            case "deviceType":
                return "Desktop";
            case "ipAddress":
                try {
                    return InetAddress.getLocalHost().getHostAddress();
                } catch (Exception e) {
                    return "Unknown";
                }
            default:
                return "";
        }
    }

    //vulnerability scan
    private void scanSoftwareForVulns(String softwareIdText) {
        if (softwareIdText == null || softwareIdText.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Enter a software ID first.").showAndWait();
            return;
        }

        int id;
        try {
            id = Integer.parseInt(softwareIdText);
        } catch (NumberFormatException e) {
            new Alert(Alert.AlertType.ERROR, "Software ID must be a number.").showAndWait();
            return;
        }

        try (Connection conn = DatabaseConnector.connect()) {
            String sql;
            if (currentIsAdmin) {
                sql = "SELECT softwareName, version, manufacturer FROM softwareassets WHERE softwareId=?";
            } else {
                // Non-admins may only scan software tied to their own assets
                sql = "SELECT s.softwareName, s.version, s.manufacturer " +
                        "FROM softwareassets s " +
                        "JOIN hardwareassets h ON s.assetId = h.Id " +
                        "WHERE s.softwareId=? AND h.EmployeeId=?";
            }

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            if (!currentIsAdmin) {
                ps.setInt(2, currentEmployeeId);
            }

            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                new Alert(Alert.AlertType.ERROR, "Software ID not found or not accessible.").showAndWait();
                return;
            }

            String name = rs.getString("softwareName");
            String version = rs.getString("version");
            String manufacturer = rs.getString("manufacturer");

            String result = queryNistVulnerabilities(name, version);

            new Alert(Alert.AlertType.INFORMATION, result).showAndWait();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error scanning: " + ex.getMessage()).showAndWait();
        }
    }
    //vulnerability scan nist query
    private String queryNistVulnerabilities(String softwareName, String version) {
        try {
            String apiKey = "...";//api key for NVD database access

            String url = "https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch="
                    + softwareName.replace(" ", "%20")
                    + "%20" + version.replace(" ", "%20");

            java.net.URL apiURL = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiURL.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("apiKey", apiKey);
            conn.setRequestProperty("User-Agent", "JavaFXApp");

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder json = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                json.append(inputLine);
            }
            in.close();

            return parseNistJson(json.toString());

        } catch (Exception e) {
            return "Error contacting NIST: " + e.getMessage();
        }
    }
    //vuln scan only return wanted info
    private String parseNistJson(String json) {
        if (!json.contains("vulnerabilities")) {
            return "No vulnerabilities found.";
        }

        // Simple detection
        if (json.contains("\"cve\"")) {
            String cve = extract(json, "\"id\":\"", "\"");
            String severity = extract(json, "\"baseSeverity\":\"", "\"");
            String desc = extract(json, "\"value\":\"", "\"");
            String published = extract(json, "\"published\":\"", "\"");

            return "⚠️ Vulnerability Found\n\n" +
                "CVE: " + cve + "\n" +
                "Severity: " + severity + "\n" +
                "Published: " + published + "\n\n" +
                "Description:\n" + desc;
        }

        return "No vulnerabilities found.";
    }

    private String extract(String text, String start, String end) {
        try {
            int s = text.indexOf(start) + start.length();
            int e = text.indexOf(end, s);
            return text.substring(s, e);
        } catch (Exception ex) {
            return "N/A";
        }
    }



    // START APPLICATION
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("Database Management System");
        stage.setScene(createLoginScene());
        stage.show();
    }

    private Scene createMainScene() {
//Asset hardware table
        TableColumn<Asset, Number> idCol = new TableColumn<>("Asset ID");
        idCol.setCellValueFactory(cd -> cd.getValue().assetIdProperty());

        TableColumn<Asset, String> dateCol = new TableColumn<>("Purchase Date");
        dateCol.setCellValueFactory(cd -> cd.getValue().purchaseDateProperty());

        TableColumn<Asset, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(cd -> cd.getValue().notesProperty());

        TableColumn<Asset, Number> empCol = new TableColumn<>("Employee ID");
        empCol.setCellValueFactory(cd -> cd.getValue().employeeIdProperty());

        TableColumn<Asset, String> sysNameCol = new TableColumn<>("System Name");
        sysNameCol.setCellValueFactory(cd -> cd.getValue().systemNameProperty());

        TableColumn<Asset, String> manuCol = new TableColumn<>("Manufacturer");
        manuCol.setCellValueFactory(cd -> cd.getValue().manufacturerProperty());

        TableColumn<Asset, String> modelCol = new TableColumn<>("Model");
        modelCol.setCellValueFactory(cd -> cd.getValue().modelProperty());

        TableColumn<Asset, String> typeCol = new TableColumn<>("Device Type");
        typeCol.setCellValueFactory(cd -> cd.getValue().deviceTypeProperty());

        TableColumn<Asset, String> ipCol = new TableColumn<>("IP Address");
        ipCol.setCellValueFactory(cd -> cd.getValue().ipAddressProperty());

        table = new TableView<>();
        table.getColumns().addAll(idCol, dateCol, notesCol, empCol, sysNameCol, manuCol, modelCol, typeCol, ipCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // load asset data
        data = FXCollections.observableArrayList();
        loadDataFromDatabase();
        table.setItems(data);

        // ASSET INPUT FIELDS
        TextField dateInput = new TextField(); dateInput.setPromptText("Purchase Date (YYYY-MM-DD)");
        TextField notesInput = new TextField(); notesInput.setPromptText("Notes");
        TextField empInput = new TextField(); empInput.setPromptText("Employee ID");
        // For non-admin users, lock Employee ID to their own
        if (!currentIsAdmin && currentEmployeeId > 0) {
            empInput.setText(String.valueOf(currentEmployeeId));
            empInput.setDisable(true);
        }

        TextField sysNameInput = new TextField(); sysNameInput.setPromptText("System Name");
        TextField manuInput = new TextField(); manuInput.setPromptText("Manufacturer");
        TextField modelInput = new TextField(); modelInput.setPromptText("Model");
        TextField typeInput = new TextField(); typeInput.setPromptText("Device Type");
        TextField ipInput = new TextField(); ipInput.setPromptText("IP Address");

        // AUTO-FILL system specs into asset inputs
        sysNameInput.setText(getSystemSpec("systemName"));
        manuInput.setText(getSystemSpec("manufacturer"));
        modelInput.setText(getSystemSpec("model"));
        typeInput.setText(getSystemSpec("deviceType"));
        ipInput.setText(getSystemSpec("ipAddress"));

        Button addButton = new Button("Add Asset");
        Button editButton = new Button("Edit Asset");
        Button deleteButton = new Button("Delete Asset");

        addButton.setOnAction(e -> addAsset(dateInput, notesInput, empInput,
                sysNameInput, manuInput, modelInput, typeInput, ipInput));
        editButton.setOnAction(e -> editSelectedAsset(dateInput, notesInput, empInput,
                sysNameInput, manuInput, modelInput, typeInput, ipInput));
        deleteButton.setOnAction(e -> deleteSelectedAsset());

        HBox inputLayout = new HBox(10,
                dateInput, notesInput, empInput,
                sysNameInput, manuInput, modelInput, typeInput, ipInput,
                addButton
        );
        inputLayout.setPadding(new Insets(10));

        HBox buttonLayout = new HBox(10, editButton, deleteButton);
        buttonLayout.setPadding(new Insets(10));
        
// SOFTWARE TABLE SETUP

    TableColumn<Software, Number> swIdCol = new TableColumn<>("Software ID");
    swIdCol.setCellValueFactory(cd -> cd.getValue().softwareIdProperty());

    TableColumn<Software, String> swNameCol = new TableColumn<>("Software Name");
    swNameCol.setCellValueFactory(cd -> cd.getValue().softwareNameProperty());

    TableColumn<Software, String> swVerCol = new TableColumn<>("Version");
    swVerCol.setCellValueFactory(cd -> cd.getValue().softwareVersionProperty());

    TableColumn<Software, String> swManuCol = new TableColumn<>("Manufacturer");
    swManuCol.setCellValueFactory(cd -> cd.getValue().manufacturerProperty());

    TableColumn<Software, Number> swAssetCol = new TableColumn<>("Asset ID");
    swAssetCol.setCellValueFactory(cd -> cd.getValue().assetIdProperty());

    softwareTable = new TableView<>();
    softwareTable.getColumns().addAll(swIdCol, swNameCol, swVerCol, swManuCol, swAssetCol);
    softwareTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    softwareData = FXCollections.observableArrayList();
    loadSoftwareFromDatabase();
    softwareTable.setItems(softwareData);

    // SOFTWARE INPUT FIELDS

    TextField swNameInput = new TextField(); swNameInput.setPromptText("Software Name (OS)");
    TextField swVerInput  = new TextField(); swVerInput.setPromptText("Version (OS version)");
    TextField swManuInput = new TextField(); swManuInput.setPromptText("Manufacturer (OS vendor)");
    TextField assetIdField = new TextField(); assetIdField.setPromptText("Asset ID");

    // AUTO-DETECT SYSTEM OS INFORMATION

    String osName = System.getProperty("os.name", "Unknown");
    String osVersion = System.getProperty("os.version", "Unknown");
    String osVendor = getOSVendor();  // Helper function

    // Autofill the text boxes
    swNameInput.setText(osName);
    swVerInput.setText(osVersion);
    swManuInput.setText(osVendor);

    // BUTTONS

    Button addSoftwareBtn = new Button("Add Software");
    Button editSoftwareBtn = new Button("Edit Software");
    Button delSoftwareBtn = new Button("Delete Software");

    // ADD SOFTWARE BUTTON ACTION

    addSoftwareBtn.setOnAction(e -> {
        try {
            String nameVal = swNameInput.getText().isEmpty() ? osName : swNameInput.getText();
            String verVal  = swVerInput.getText().isEmpty() ? osVersion : swVerInput.getText();
            String manuVal = swManuInput.getText().isEmpty() ? osVendor : swManuInput.getText();

            if (assetIdField.getText().isEmpty()) {
                new Alert(Alert.AlertType.ERROR, "Asset ID is required.").showAndWait();
                return;
            }

            int assetIdVal = Integer.parseInt(assetIdField.getText());

            addSoftwareToDb(nameVal, verVal, manuVal, assetIdVal);

            // Reset to OS autofill
            swNameInput.setText(osName);
            swVerInput.setText(osVersion);
            swManuInput.setText(osVendor);
            assetIdField.clear();

            loadSoftwareFromDatabase();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error adding software: " + ex.getMessage()).showAndWait();
        }
    });

    // EDIT SOFTWARE BUTTON ACTION

    editSoftwareBtn.setOnAction(e -> {
        Software selected = softwareTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select a software record to edit.").showAndWait();
            return;
        }

        String nameVal = swNameInput.getText().isEmpty() ? selected.getSoftwareName() : swNameInput.getText();
        String verVal  = swVerInput.getText().isEmpty() ? selected.getSoftwareVersion() : swVerInput.getText();
        String manuVal = swManuInput.getText().isEmpty() ? selected.getManufacturer() : swManuInput.getText();

        int assetIdVal = assetIdField.getText().isEmpty()
                ? selected.getAssetId()
                : Integer.parseInt(assetIdField.getText());

        updateSoftwareInDb(selected.getSoftwareId(), nameVal, verVal, manuVal, assetIdVal);

        // Reset to OS autofill
        swNameInput.setText(osName);
        swVerInput.setText(osVersion);
        swManuInput.setText(osVendor);
        assetIdField.clear();

        loadSoftwareFromDatabase();
    });

    // DELETE SOFTWARE BUTTON ACTION
    delSoftwareBtn.setOnAction(e -> {
        Software selected = softwareTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select a software record to delete.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete software ID " + selected.getSoftwareId() + "?",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait();

        if (confirm.getResult() == ButtonType.YES) {
            deleteSoftwareFromDb(selected.getSoftwareId());
            loadSoftwareFromDatabase();
        }
    });


//vulnerability scan button
        TextField scanSoftwareIdField = new TextField();
        scanSoftwareIdField.setPromptText("Software ID");
        Button scanVulnBtn = new Button("Scan Vulnerabilities");
        scanVulnBtn.setOnAction(e -> scanSoftwareForVulns(scanSoftwareIdField.getText()));

    
        HBox swInputRow = new HBox(10, swNameInput, swVerInput, swManuInput, assetIdField, addSoftwareBtn);
        swInputRow.setPadding(new Insets(8));
        HBox swButtonRow = new HBox(10, editSoftwareBtn, delSoftwareBtn);
        swButtonRow.setPadding(new Insets(8));

        VBox swSection = new VBox(8, new Label("Software Assets"), softwareTable, swInputRow, swButtonRow);
        swSection.setPadding(new Insets(10));
        swSection.setStyle("-fx-border-color: gray; -fx-border-width: 1;");
//vulnerabilty button
        HBox scanRow = new HBox(10, scanSoftwareIdField, scanVulnBtn);


        // EMPLOYEE TABLE
        TableColumn<Employee, Number> empIdCol = new TableColumn<>("Employee ID");
        empIdCol.setCellValueFactory(cd -> cd.getValue().employeeIdProperty());

        TableColumn<Employee, String> empFirstCol = new TableColumn<>("First Name");
        empFirstCol.setCellValueFactory(cd -> cd.getValue().firstNameProperty());

        TableColumn<Employee, String> empLastCol = new TableColumn<>("Last Name");
        empLastCol.setCellValueFactory(cd -> cd.getValue().lastNameProperty());

        TableColumn<Employee, String> empDeptCol = new TableColumn<>("Department");
        empDeptCol.setCellValueFactory(cd -> cd.getValue().departmentProperty());

        TableColumn<Employee, String> empMailCol = new TableColumn<>("Email");
        empMailCol.setCellValueFactory(cd -> cd.getValue().emailProperty());

        employeeTable = new TableView<>();
        employeeTable.getColumns().addAll(empIdCol, empFirstCol, empLastCol, empDeptCol, empMailCol);
        employeeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        employeeData = FXCollections.observableArrayList();
        loadEmployeeDataFromDatabase();
        employeeTable.setItems(employeeData);

        TextField empFN = new TextField(); empFN.setPromptText("First Name");
        TextField empLN = new TextField(); empLN.setPromptText("Last Name");
        TextField empDept = new TextField(); empDept.setPromptText("Department");
        TextField empEmail = new TextField(); empEmail.setPromptText("Email");

        Button addEmpBtn = new Button("Add Employee");
        Button editEmpBtn = new Button("Edit Employee");
        Button delEmpBtn = new Button("Delete Employee");

        addEmpBtn.setOnAction(e -> addEmployee(empFN, empLN, empDept, empEmail));
        editEmpBtn.setOnAction(e -> editSelectedEmployee(empFN, empLN, empDept, empEmail));
        delEmpBtn.setOnAction(e -> deleteSelectedEmployee());

        // Only admins can manage employee records
        if (!currentIsAdmin) {
            addEmpBtn.setDisable(true);
            editEmpBtn.setDisable(true);
            delEmpBtn.setDisable(true);
        }

        HBox empInputLayout = new HBox(10, empFN, empLN, empDept, empEmail, addEmpBtn);
        empInputLayout.setPadding(new Insets(10));

        HBox empButtonLayout = new HBox(10, editEmpBtn, delEmpBtn);
        empButtonLayout.setPadding(new Insets(10));

        VBox empSection = new VBox(10, new Label("Employees"), employeeTable, empInputLayout, empButtonLayout);
        empSection.setPadding(new Insets(15));
        empSection.setStyle("-fx-border-color: gray; -fx-border-width: 1;");

        // INSTRUCTIONS PANEL
        VBox instructions = new VBox(10);
        instructions.setPadding(new Insets(15));
        instructions.setStyle("-fx-background-color:#f4f4f4; -fx-border-color:gray; -fx-border-width:1;");

        Label title = new Label("Usage Guide");
        title.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");

        Label text = new Label(
                "Managing Company Assets\n" +
                        "Fill in empty field\n" +
                        "auto filled fields can be edited\n" +
                        "press the button that corrosponds to the wanted action \n" +
                        "same rules apply for all tables"
        );
        text.setWrapText(true);

        instructions.getChildren().addAll(title, text);

        // MAIN LAYOUT 
        VBox mainLayout = new VBox(20,
                new Label("Assets"),
                table,
                inputLayout,
                buttonLayout,
                swSection,
                scanRow,
                empSection
        );

        HBox root = new HBox(20, mainLayout, instructions);
        root.setPadding(new Insets(15));

        return new Scene(root, 1400, 900);
    }

    private Scene createLoginScene() {
        Label heading = new Label("Secure Email Login");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        TextField emailField = new TextField();
        emailField.setPromptText("Email");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        Label feedbackLabel = new Label();
        feedbackLabel.setStyle("-fx-text-fill: crimson;");

        Button loginButton = new Button("Login");
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> attemptLogin(emailField.getText(), passwordField.getText(), feedbackLabel));
        passwordField.setOnAction(e -> attemptLogin(emailField.getText(), passwordField.getText(), feedbackLabel));

        VBox form = new VBox(12, heading, emailField, passwordField, loginButton, feedbackLabel);
        form.setPadding(new Insets(30));
        form.setStyle("-fx-background-color: white; -fx-border-color: #d0d0d0; -fx-border-width: 1;");

        BorderPane wrapper = new BorderPane(form);
        wrapper.setPadding(new Insets(60));

        return new Scene(wrapper, 400, 250);
    }

    private void attemptLogin(String email, String password, Label feedbackLabel) {
        if (verifyCredentials(email, password)) {
            feedbackLabel.setText("");
            primaryStage.setScene(createMainScene());
        } else {
            feedbackLabel.setText("Invalid email or password.");
        }
    }

    private boolean verifyCredentials(String email, String password) {
        if (email == null || password == null) {
            return false;
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (normalizedEmail.isEmpty()) {
            return false;
        }
        String storedHash = CREDENTIAL_STORE.get(normalizedEmail);
        if (storedHash == null || !storedHash.equals(hashPassword(password))) {
            return false;
        }

        // Look up employee record for this email to determine role/scope
        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT employeeId, department, email FROM employee WHERE LOWER(email) = ?"
             )) {
            ps.setString(1, normalizedEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Valid email/password in in-memory store, but not tied to an employee
                    return false;
                }
                currentEmployeeId = rs.getInt("employeeId");
                currentDepartment = rs.getString("department");
                currentUserEmail = rs.getString("email");
                currentIsAdmin = currentDepartment != null
                        && currentDepartment.equalsIgnoreCase("IT");
                return true;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private static String hashPassword(String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashedBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }

    // DATABASE INTERACTIONS
    private void loadDataFromDatabase() {
        if (data == null) data = FXCollections.observableArrayList();
        data.clear();

        String sql;
        if (currentIsAdmin) {
            sql = "SELECT * FROM hardwareassets";
        } else {
            // Non-admin users only see assets linked to their employeeId
            sql = "SELECT * FROM hardwareassets WHERE EmployeeId = ?";
        }

        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (!currentIsAdmin) {
                ps.setInt(1, currentEmployeeId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    data.add(new Asset(
                            rs.getInt("Id"),
                            rs.getString("PurchaseDate"),
                            rs.getString("Notes"),
                            rs.getInt("EmployeeId"),
                            rs.getString("SystemName"),
                            rs.getString("Manufacturer"),
                            rs.getString("Model"),
                            rs.getString("DeviceType"),
                            rs.getString("IPAddress")
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // SOFTWARE DB
    private void loadSoftwareFromDatabase() {
        if (softwareData == null) {
            softwareData = FXCollections.observableArrayList();
        }
        softwareData.clear();

        String sql;
        if (currentIsAdmin) {
            sql = "SELECT softwareId, softwareName, version, manufacturer, assetId FROM softwareassets";
        } else {
            // Non-admin users only see software for assets they own
            sql = "SELECT s.softwareId, s.softwareName, s.version, s.manufacturer, s.assetId " +
                    "FROM softwareassets s " +
                    "JOIN hardwareassets h ON s.assetId = h.Id " +
                    "WHERE h.EmployeeId = ?";
        }

        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (!currentIsAdmin) {
                ps.setInt(1, currentEmployeeId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    softwareData.add(new Software(
                            rs.getInt("softwareId"),
                            rs.getString("softwareName"),
                            rs.getString("version"),
                            rs.getString("manufacturer"),
                            rs.getInt("assetId")
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void addSoftwareToDb(String name, String version, String manufacturer, int assetId) {
        String sql = "INSERT INTO softwareassets (softwareName, version, manufacturer, assetId) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // Enforce that non-admins can only attach software to assets they own
            if (!currentIsAdmin && !canAccessAsset(assetId)) {
                new Alert(Alert.AlertType.ERROR, "You can only add software to your own assets.").showAndWait();
                return;
            }

            ps.setString(1, name);
            ps.setString(2, version);
            ps.setString(3, manufacturer);
            ps.setInt(4, assetId);
            ps.executeUpdate();

            loadSoftwareFromDatabase();     
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error adding software: " + ex.getMessage()).showAndWait();
        }
    }

    private void updateSoftwareInDb(int softwareId, String name, String version, String manufacturer, int assetId) {
        String sql = "UPDATE softwareassets SET softwareName=?, version=?, manufacturer=?, assetId=? WHERE softwareId=?";
        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // Enforce that non-admins can only move/edit software on assets they own
            if (!currentIsAdmin && !canAccessAsset(assetId)) {
                new Alert(Alert.AlertType.ERROR, "You can only edit software on your own assets.").showAndWait();
                return;
            }

            ps.setString(1, name);
            ps.setString(2, version);
            ps.setString(3, manufacturer);
            ps.setInt(4, assetId);
            ps.setInt(5, softwareId);
            ps.executeUpdate();

            loadSoftwareFromDatabase();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error updating software: " + ex.getMessage()).showAndWait();
        }
    }

    private void deleteSoftwareFromDb(int softwareId) {
        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement psCheck = conn.prepareStatement("SELECT assetId FROM softwareassets WHERE softwareId=?")) {

            psCheck.setInt(1, softwareId);
            try (ResultSet rs = psCheck.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                int assetId = rs.getInt("assetId");
                if (!currentIsAdmin && !canAccessAsset(assetId)) {
                    new Alert(Alert.AlertType.ERROR, "You can only delete software from your own assets.").showAndWait();
                    return;
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM softwareassets WHERE softwareId=?")) {
                ps.setInt(1, softwareId);
                ps.executeUpdate();
                loadSoftwareFromDatabase();
            }
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error deleting software: " + ex.getMessage()).showAndWait();
        }
    }

    // EMPLOYEE DB
    private void loadEmployeeDataFromDatabase() {
        if (employeeData == null) employeeData = FXCollections.observableArrayList();
        employeeData.clear();

        String sql;
        if (currentIsAdmin) {
            sql = "SELECT * FROM employee";
        } else {
            // Non-admin users only see their own employee record
            sql = "SELECT * FROM employee WHERE employeeId = ?";
        }

        try (Connection conn = DatabaseConnector.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (!currentIsAdmin) {
                ps.setInt(1, currentEmployeeId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    employeeData.add(new Employee(
                            rs.getInt("employeeId"),
                            rs.getString("firstName"),
                            rs.getString("lastName"),
                            rs.getString("department"),
                            rs.getString("email")
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ASSET
    private void addAsset(
            TextField date, TextField notes, TextField empIdField,
            TextField sysName, TextField manu, TextField model, TextField type, TextField ip
    ) {
        try {
            String purchaseDate = date.getText();
            String note = notes.getText();
            int employeeId;
            if (currentIsAdmin) {
                employeeId = Integer.parseInt(empIdField.getText());
            } else {
                // Non-admin users can only create assets for themselves
                employeeId = currentEmployeeId;
            }

            String sysNameVal = sysName.getText().isEmpty() ? getSystemSpec("systemName") : sysName.getText();
            String manuVal    = manu.getText().isEmpty() ? getSystemSpec("manufacturer") : manu.getText();
            String modelVal   = model.getText().isEmpty() ? getSystemSpec("model") : model.getText();
            String typeVal    = type.getText().isEmpty() ? getSystemSpec("deviceType") : type.getText();
            String ipVal      = ip.getText().isEmpty() ? getSystemSpec("ipAddress") : ip.getText();

            String sql = "INSERT INTO hardwareassets (PurchaseDate, Notes, EmployeeId, SystemName, Manufacturer, Model, DeviceType, IPAddress) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, purchaseDate);
                ps.setString(2, note);
                ps.setInt(3, employeeId);
                ps.setString(4, sysNameVal);
                ps.setString(5, manuVal);
                ps.setString(6, modelVal);
                ps.setString(7, typeVal);
                ps.setString(8, ipVal);

                ps.executeUpdate();
            }

            loadDataFromDatabase();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error adding asset: " + ex.getMessage()).showAndWait();
        }
    }

    private void editSelectedAsset(
            TextField date, TextField notes, TextField empIdField,
            TextField sysName, TextField manu, TextField model, TextField type, TextField ip
    ) {
        Asset selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select an asset first.").showAndWait();
            return;
        }
        // Non-admin users can only edit their own assets
        if (!currentIsAdmin && selected.getEmployeeId() != currentEmployeeId) {
            new Alert(Alert.AlertType.ERROR, "You can only edit your own assets.").showAndWait();
            return;
        }

        try {
            String purchaseDate = date.getText().isEmpty() ? selected.getPurchaseDate() : date.getText();
            String note         = notes.getText().isEmpty() ? selected.getNotes() : notes.getText();
            int employeeId;
            if (currentIsAdmin) {
                employeeId = empIdField.getText().isEmpty()
                        ? selected.getEmployeeId()
                        : Integer.parseInt(empIdField.getText());
            } else {
                // Employee ID is fixed for non-admin users
                employeeId = currentEmployeeId;
            }

            String sysNameVal = sysName.getText().isEmpty() ? selected.getSystemName() : sysName.getText();
            String manuVal    = manu.getText().isEmpty() ? selected.getManufacturer() : manu.getText();
            String modelVal   = model.getText().isEmpty() ? selected.getModel() : model.getText();
            String typeVal    = type.getText().isEmpty() ? selected.getDeviceType() : type.getText();
            String ipVal      = ip.getText().isEmpty() ? selected.getIpAddress() : ip.getText();

            String sql = "UPDATE hardwareassets SET PurchaseDate=?, Notes=?, EmployeeId=?, SystemName=?, Manufacturer=?, Model=?, DeviceType=?, IPAddress=? WHERE Id=?";

            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, purchaseDate);
                ps.setString(2, note);
                ps.setInt(3, employeeId);
                ps.setString(4, sysNameVal);
                ps.setString(5, manuVal);
                ps.setString(6, modelVal);
                ps.setString(7, typeVal);
                ps.setString(8, ipVal);
                ps.setInt(9, selected.getAssetId());

                ps.executeUpdate();
            }

            loadDataFromDatabase();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error updating asset: " + ex.getMessage()).showAndWait();
        }
    }

    private void deleteSelectedAsset() {
        Asset selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select an asset.").showAndWait();
            return;
        }
        // Non-admin users can only delete their own assets
        if (!currentIsAdmin && selected.getEmployeeId() != currentEmployeeId) {
            new Alert(Alert.AlertType.ERROR, "You can only delete your own assets.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete asset ID " + selected.getAssetId() + "?",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait();

        if (confirm.getResult() == ButtonType.YES) {
            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM hardwareassets WHERE Id=?")) {

                ps.setInt(1, selected.getAssetId());
                ps.executeUpdate();
                loadDataFromDatabase();

            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Error deleting asset: " + ex.getMessage()).showAndWait();
            }
        }
    }

    private void addEmployee(TextField fn, TextField ln, TextField dept, TextField email) {
        if (!currentIsAdmin) {
            new Alert(Alert.AlertType.ERROR, "Only admins can add employees.").showAndWait();
            return;
        }
        try {
            String sql = "INSERT INTO employee (firstName, lastName, department, email) VALUES (?, ?, ?, ?)";

            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, fn.getText());
                ps.setString(2, ln.getText());
                ps.setString(3, dept.getText());
                ps.setString(4, email.getText());
                ps.executeUpdate();
            }

            loadEmployeeDataFromDatabase();

            fn.clear(); ln.clear(); dept.clear(); email.clear();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error adding employee: " + ex.getMessage()).showAndWait();
        }
    }

    private void editSelectedEmployee(TextField fn, TextField ln, TextField dept, TextField email) {
        Employee selected = employeeTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select an employee first.").showAndWait();
            return;
        }
        if (!currentIsAdmin) {
            new Alert(Alert.AlertType.ERROR, "Only admins can edit employees.").showAndWait();
            return;
        }

        try {
            String sql = "UPDATE employee SET firstName=?, lastName=?, department=?, email=? WHERE employeeId=?";

            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, fn.getText().isEmpty() ? selected.getFirstName() : fn.getText());
                ps.setString(2, ln.getText().isEmpty() ? selected.getLastName() : ln.getText());
                ps.setString(3, dept.getText().isEmpty() ? selected.getDepartment() : dept.getText());
                ps.setString(4, email.getText().isEmpty() ? selected.getEmail() : email.getText());
                ps.setInt(5, selected.getEmployeeId());

                ps.executeUpdate();
            }

            loadEmployeeDataFromDatabase();

            fn.clear(); ln.clear(); dept.clear(); email.clear();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Error editing employee: " + ex.getMessage()).showAndWait();
        }
    }

    private void deleteSelectedEmployee() {
        Employee selected = employeeTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select an employee.").showAndWait();
            return;
        }
        if (!currentIsAdmin) {
            new Alert(Alert.AlertType.ERROR, "Only admins can delete employees.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete employee ID " + selected.getEmployeeId() + "?",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait();

        if (confirm.getResult() == ButtonType.YES) {
            try (Connection conn = DatabaseConnector.connect();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM employee WHERE employeeId=?")) {

                ps.setInt(1, selected.getEmployeeId());
                ps.executeUpdate();
                loadEmployeeDataFromDatabase();

            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Error deleting employee: " + ex.getMessage()).showAndWait();
            }
        }
    }
    // main
    public static void main(String[] args) {
        launch(args);
    }
}
