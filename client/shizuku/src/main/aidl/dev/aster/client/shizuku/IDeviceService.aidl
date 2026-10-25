package dev.aster.client.shizuku;
import android.os.Bundle;
interface IDeviceService {
 void destroy() = 16777114;
 String execute(String operation, String arguments) = 1;
 Bundle screenshot() = 2;
}
