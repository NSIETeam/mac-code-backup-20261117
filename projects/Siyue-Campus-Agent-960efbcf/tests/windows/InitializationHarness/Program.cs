using Siyue.Agent;
using System.Security.Cryptography;
using System.Text.Json;
using AgentProgram = Siyue.Agent.Program;
static class InitializationHarness
{
    [STAThread] static int Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        var update = args.Contains("update"); Exception? failure = null;
        var current = AgentProgram.ReadConfig(); var beforeId = current?.DeviceId; var beforeToken = current?.Token;
        if (update && current == null) throw new Exception("Expected registered identity before editing");
        using var form = new DestinationForm(current);
        T Field<T>(string name) where T : Control => (T)form.Controls.Find(name, true).Single();
        void Capture(string file) { using var bitmap = new Bitmap(form.Width, form.Height); form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size)); bitmap.Save(Path.Combine("diagnostics", file)); }
        form.Shown += async (_, _) => {
            try {
                var queue = Field<ComboBox>("PrinterQueue");
                for (int i = 0; i < 100 && queue.Items.Count == 0; i++) await Task.Delay(100);
                if (queue.Items.Count == 0) throw new Exception("Queue discovery failed");
                var campus = Field<TextBox>("CampusName"); var alias = Field<TextBox>("PrinterAlias");
                if (!update) {
                    Capture("01-initialization-required.png"); Field<Button>("SaveProfile").PerformClick(); await Task.Delay(100);
                    if (form.IsDisposed || !Field<Label>("ValidationMessage").Text.Contains("所属校区")) throw new Exception("Empty campus was accepted");
                    campus.Text = "远端验收校区";
                    Field<Button>("SaveProfile").PerformClick(); await Task.Delay(100);
                    if (form.IsDisposed) throw new Exception("Incomplete initialization was accepted");
                } else if (campus.Text != "远端验收校区" || alias.Text != "银座晶都12B 东") throw new Exception("Saved profile was not restored");
                queue.SelectedItem = "Siyue Acceptance PDF";
                if (queue.SelectedIndex < 0) throw new Exception("Named queue missing");
                alias.Text = update ? "银座晶都12B 西" : "银座晶都12B 东";
                Capture(update ? "03-edit-alias.png" : "02-initialization-filled.png");
                Field<Button>("SaveProfile").PerformClick();
                for (int i = 0; i < 180 && !form.IsDisposed; i++) await Task.Delay(500);
                if (!form.IsDisposed) throw new Exception("Save did not close successfully: " + Field<Label>("ValidationMessage").Text);
            } catch (Exception e) { failure = e; if (!form.IsDisposed) { Capture("initialization-error.png"); form.Close(); } }
        };
        Application.Run(form);
        if (failure != null) throw failure;
        if (form.Result?.Profile == null || !AutomaticEnrollment.Initialized) throw new Exception("Profile was not saved");
        if (update) {
            var after = AgentProgram.ReadConfig();
            if (after?.DeviceId != beforeId || after?.Token != beforeToken) throw new Exception("Editing alias replaced device identity");
        }
        File.WriteAllText(Path.Combine("diagnostics", update ? "profile-edit-result.json" : "profile-initialization-result.json"), JsonSerializer.Serialize(new { initialized = true, identityPreserved = update, profile = form.Result.Profile }, AgentProgram.Json));
        return 0;
    }
}
