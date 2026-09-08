package net.lovelace.vesuvio;

import ai.onnxruntime.OrtSession;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.onnx.MLResult;
import net.lovelace.vesuvio.check.onnx.ModelConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

public class MLManagerTest {

    @Test
    public void testClickModelAndAimModelInference() throws Exception {
        try (MLManager mlManager = new MLManager()) {
            Path tempDir = Files.createTempDirectory("vesuvio-ml-test");

            // Extract click_model.onnx
            Path clickModelPath = tempDir.resolve("click_model.onnx");
            try (InputStream in = getClass().getResourceAsStream("/models/click_model.onnx")) {
                assertNotNull(in, "click_model.onnx resource should exist");
                Files.copy(in, clickModelPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Extract aim_model.onnx
            Path aimModelPath = tempDir.resolve("aim_model.onnx");
            try (InputStream in = getClass().getResourceAsStream("/models/aim_model.onnx")) {
                assertNotNull(in, "aim_model.onnx resource should exist");
                Files.copy(in, aimModelPath, StandardCopyOption.REPLACE_EXISTING);
            }

            mlManager.registerModel(new ModelConfig("click_model", true, clickModelPath.toString(), 0.85, 1.5, "float_input"));
            mlManager.registerModel(new ModelConfig("aim_model", true, aimModelPath.toString(), 0.82, 1.4, "float_input"));

            try {
                mlManager.loadModel("click_model", clickModelPath);
                mlManager.loadModel("aim_model", aimModelPath);
            } catch (Exception e) {
                System.out.println("ONNX Runtime native load note: " + e.getMessage());
            }

            // Test click features (16 floats)
            float[] clickFeatures = new float[16];
            clickFeatures[0] = 50.0f; // mean delay
            clickFeatures[1] = 0.5f;  // std dev
            clickFeatures[4] = 0.95f; // dup ratio
            clickFeatures[5] = 0.8f;  // entropy
            clickFeatures[14] = 20.0f;// CPS

            MLResult clickRes = mlManager.evaluateAsync("click_model", clickFeatures).get();
            assertNotNull(clickRes);
            System.out.println("Click Model Result: prob=" + clickRes.probability() + ", expl=" + clickRes.explanation());
            assertTrue(clickRes.probability() >= 0.0 && clickRes.probability() <= 1.0);

            // Test aim features (8 floats expected by aim_model.onnx)
            float[] aimFeatures = new float[8];
            aimFeatures[0] = 12.0f; // meanYaw
            aimFeatures[1] = 5.0f;  // meanPitch
            aimFeatures[2] = 4.0f;  // varYaw
            aimFeatures[3] = 2.0f;  // varPitch
            aimFeatures[4] = 0.50f; // snapRatio
            aimFeatures[5] = 0.05f; // zeroRatio
            aimFeatures[6] = 28.0f; // jerk
            aimFeatures[7] = 0.001f;// gcd consistency (failing)

            MLResult aimRes = mlManager.evaluateAsync("aim_model", aimFeatures).get();
            assertNotNull(aimRes);
            System.out.println("Aim Model Result: prob=" + aimRes.probability() + ", expl=" + aimRes.explanation());
            assertTrue(aimRes.probability() >= 0.0 && aimRes.probability() <= 1.0);

            var field = MLManager.class.getDeclaredField("sessions");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var sessions = (java.util.Map<String, OrtSession>) field.get(mlManager);
            OrtSession clickSession = sessions.get("click_model");
            if (clickSession != null) {
                System.out.println("click_model inputs: " + clickSession.getInputInfo());
                System.out.println("click_model outputs: " + clickSession.getOutputInfo());
            }
            OrtSession aimSession = sessions.get("aim_model");
            if (aimSession != null) {
                System.out.println("aim_model inputs: " + aimSession.getInputInfo());
                System.out.println("aim_model outputs: " + aimSession.getOutputInfo());
            }
        }
    }
}
