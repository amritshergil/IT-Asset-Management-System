import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnector {
   private static final String URL = "DB_URL";
   private static final String USER = "DB_USER";
   private static final String PASSWORD = "DB_PASSWORD"; 
//placeholder data
   
   public static Connection connect() {
       try {
         Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
         System.out.println("Connected to MySQL");
         return conn;
       } catch (SQLException e) {
            System.out.println("Connection failed: " + e.getMessage());
            return null;
       }
    }
}
