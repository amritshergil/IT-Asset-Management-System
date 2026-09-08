import javafx.beans.property.*;

public class Employee {
    private final IntegerProperty employeeId;
    private final StringProperty firstName;
    private final StringProperty lastName;
    private final StringProperty department;
    private final StringProperty email;

    public Employee(int employeeId, String firstName, String lastName, String department, String email) {
        this.employeeId = new SimpleIntegerProperty(employeeId);
        this.firstName = new SimpleStringProperty(firstName);
        this.lastName = new SimpleStringProperty(lastName);
        this.department = new SimpleStringProperty(department);
        this.email = new SimpleStringProperty(email);
    }

    // Getters
    public int getEmployeeId() { return employeeId.get(); }
    public String getFirstName() { return firstName.get(); }
    public String getLastName() { return lastName.get(); }
    public String getDepartment() { return department.get(); }
    public String getEmail() { return email.get(); }

    // Properties for TableView 
    public IntegerProperty employeeIdProperty() { return employeeId; }
    public StringProperty firstNameProperty() { return firstName; }
    public StringProperty lastNameProperty() { return lastName; }
    public StringProperty departmentProperty() { return department; }
    public StringProperty emailProperty() { return email; }
}