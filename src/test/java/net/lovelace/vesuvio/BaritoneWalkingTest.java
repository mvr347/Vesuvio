package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import net.lovelace.vesuvio.check.statistical.BaritoneCheck;
import net.lovelace.vesuvio.check.statistical.KillauraAngleCheck;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BaritoneWalkingTest {
  @Test
  void vanillaForwardMovementIsNotEvidenceOfAPathfinder() throws Exception {
    var check = new BaritoneCheck();
    var data = new UserData(UUID.randomUUID(), "LegitimateWalker");
    Player player =
        (Player)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {Player.class},
                (p, m, a) ->
                    m.getName().equals("getLocation") ? new Location(null, 0, 70, 0, 0, 0) : null);
    // Holding W with the mouse still gives exactly this look/motion alignment in vanilla.
    assertEquals(
        0, KillauraAngleCheck.angularDiff(0, KillauraAngleCheck.requiredYaw(0, 0.2)), 0.00001);
    var parts = EnvironmentSnapshot.class.getRecordComponents();
    var types = new Class<?>[parts.length];
    var values = new Object[parts.length];
    for (int i = 0; i < parts.length; i++) {
      types[i] = parts[i].getType();
      values[i] = parts[i].getAccessor().invoke(EnvironmentSnapshot.EMPTY);
      if (parts[i].getName().equals("valid")) values[i] = true;
    }
    var constructor = EnvironmentSnapshot.class.getDeclaredConstructor(types);
    for (int tick = 0; tick < 200; tick++) {
      for (int i = 0; i < parts.length; i++)
        if (parts[i].getName().equals("captureMillis")) values[i] = System.currentTimeMillis();
      data.setEnvironment(constructor.newInstance(values));
      assertFalse(
          check.check(player, data, 0, 0.2).isFlag(), "Normal W movement flagged at tick " + tick);
    }
  }
}
