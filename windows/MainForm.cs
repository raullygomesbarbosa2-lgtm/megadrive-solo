using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace MegaDriveSolo;

public sealed class MainForm : Form
{
    const int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_SYSKEYDOWN = 0x0104, WM_SYSKEYUP = 0x0105;
    [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")] static extern uint XInputGetState(uint userIndex, out XInputState state);
    [StructLayout(LayoutKind.Sequential)] struct XInputState { public uint packet; public XInputGamepad gamepad; }
    [StructLayout(LayoutKind.Sequential)] struct XInputGamepad { public ushort buttons; public byte leftTrigger, rightTrigger; public short thumbLX, thumbLY, thumbRX, thumbRY; }

    readonly Panel screen = new() { Dock = DockStyle.Fill, BackColor = Color.Black };
    Label hint = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.FromArgb(120, 235, 255), Font = new Font("Segoe UI", 19, FontStyle.Bold), Text = "MEGA DRIVE SOLO\r\n\r\nEscolha uma ROM sua para jogar" };
    readonly Timer frameTimer = new() { Interval = 16 };
    readonly bool[] keyboard = new bool[16];
    WaveOutAudio? audio;
    readonly retro_video_refresh_t videoCallback;
    readonly retro_audio_sample_t audioCallback;
    readonly retro_audio_sample_batch_t audioBatchCallback;
    readonly retro_input_poll_t inputPollCallback;
    readonly retro_input_state_t inputStateCallback;
    readonly retro_environment_t environmentCallback;
    readonly retro_set_environment_t setEnvironmentCallback;
    readonly retro_basic_t initCallback, deinitCallback, unloadGameCallback, runCallback;
    readonly retro_load_game_t loadGameCallback;
    readonly retro_set_video_refresh_t setVideoCallback;
    readonly retro_set_audio_sample_t setAudioCallback;
    readonly retro_set_audio_sample_batch_t setAudioBatchCallback;
    readonly retro_set_input_poll_t setInputPollCallback;
    readonly retro_set_input_state_t setInputStateCallback;
    readonly retro_set_controller_port_device_t setControllerCallback;
    GameVideoView? video;
    IntPtr core;
    bool initialized, gameLoaded;
    string? currentRom;

    public MainForm()
    {
        videoCallback = VideoRefresh; audioCallback = AudioSample; audioBatchCallback = AudioBatch; inputPollCallback = InputPoll; inputStateCallback = InputState; environmentCallback = EnvironmentCallback;
        setEnvironmentCallback = cb => Export<retro_set_environment_t>("retro_set_environment")(cb);
        initCallback = () => { var f = Export<retro_basic_t>("retro_init"); f(); }; deinitCallback = () => { var f = Export<retro_basic_t>("retro_deinit"); f(); }; unloadGameCallback = () => { var f = Export<retro_basic_t>("retro_unload_game"); f(); }; runCallback = () => { var f = Export<retro_basic_t>("retro_run"); f(); };
        loadGameCallback = (ref retro_game_info info) => { var f = Export<retro_load_game_t>("retro_load_game"); return f(ref info); };
        setEnvironmentCallback = cb => { var f = Export<retro_set_environment_t>("retro_set_environment"); f(cb); };
        setVideoCallback = cb => { var f = Export<retro_set_video_refresh_t>("retro_set_video_refresh"); f(cb); };
        setAudioCallback = cb => { var f = Export<retro_set_audio_sample_t>("retro_set_audio_sample"); f(cb); };
        setAudioBatchCallback = cb => { var f = Export<retro_set_audio_sample_batch_t>("retro_set_audio_sample_batch"); f(cb); };
        setInputPollCallback = cb => { var f = Export<retro_set_input_poll_t>("retro_set_input_poll"); f(cb); };
        setInputStateCallback = cb => { var f = Export<retro_set_input_state_t>("retro_set_input_state"); f(cb); };
        setControllerCallback = (port, device) => { var f = Export<retro_set_controller_port_device_t>("retro_set_controller_port_device"); f(port, device); };
        Text = "Mega Drive Solo"; BackColor = Color.FromArgb(3, 13, 22); MinimumSize = new Size(960, 600); WindowState = FormWindowState.Maximized; KeyPreview = true;
        var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 58, BackColor = Color.FromArgb(5, 22, 32), Padding = new Padding(8) };
        bar.Controls.Add(new Label { Text = "MEGA DRIVE SOLO", ForeColor = Color.FromArgb(55, 236, 255), Font = new Font("Segoe UI", 15, FontStyle.Bold), AutoSize = true, Padding = new Padding(4, 6, 18, 0) });
        var open = MakeButton("ABRIR JOGO", Color.FromArgb(50, 238, 255)); open.Click += (_, _) => ChooseRom();
        var fullscreen = MakeButton("TELA CHEIA", Color.FromArgb(88, 255, 160)); fullscreen.Click += (_, _) => ToggleFullscreen();
        bar.Controls.Add(open); bar.Controls.Add(fullscreen); screen.Controls.Add(hint); Controls.Add(screen); Controls.Add(bar);
        frameTimer.Tick += (_, _) => { if (gameLoaded) try { runCallback(); } catch (Exception ex) { frameTimer.Stop(); MessageBox.Show(this, ex.Message, "Erro no jogo"); } };
        Shown += (_, _) => InitializeCore(); FormClosing += (_, _) => ShutdownCore();
    }

    Button MakeButton(string text, Color accent) => new() { Text = text, AutoSize = true, FlatStyle = FlatStyle.Flat, ForeColor = Color.White, BackColor = Color.FromArgb(4, 32, 43), Font = new Font("Segoe UI", 10, FontStyle.Bold), Margin = new Padding(6, 3, 6, 3), Padding = new Padding(12, 4, 12, 4), FlatAppearance = { BorderColor = accent, BorderSize = 2 } };

    void InitializeCore()
    {
        try
        {
            string dll = Path.Combine(AppContext.BaseDirectory, "cores", "genesis_plus_gx_libretro.dll");
            if (!File.Exists(dll)) { hint.Text = "O núcleo Genesis Plus GX não foi encontrado. Reinstale o Mega Drive Solo."; return; }
            core = LoadLibrary(dll); if (core == IntPtr.Zero) throw new InvalidOperationException("O Windows não carregou Genesis Plus GX: " + Marshal.GetLastWin32Error());
            core = LoadLibrary(dll); if (core == IntPtr.Zero) throw new InvalidOperationException("O Windows não carregou Genesis Plus GX.");
            setVideoCallback(videoCallback); setAudioCallback(audioCallback); setAudioBatchCallback(audioBatchCallback); setInputPollCallback(inputPollCallback); setInputStateCallback(inputStateCallback);
            setEnvironmentCallback(environmentCallback); initCallback(); initialized = true;
            setControllerCallback(0, (2u << 8) | 1u);
            try { audio = new WaveOutAudio(); } catch { audio = null; }
            hint.Text = "MEGA DRIVE SOLO\r\n\r\nEscolha uma ROM sua\r\n\r\nSetas: mover   Z/X/C: B/A/C   A/S/D: X/Y/Z\r\nEnter: Start   Backspace: Select   F11: tela cheia\r\nControle USB/Bluetooth XInput compatível";
        }
        catch (Exception e) { hint.Text = "Falha ao iniciar o núcleo:\r\n" + e.Message; }
    }

    void ChooseRom()
    {
        using var dialog = new OpenFileDialog { Title = "Escolher ROM de Mega Drive", Filter = "Jogos Mega Drive|*.bin;*.gen;*.md;*.smd;*.zip|Todos os arquivos|*.*" };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            string path = dialog.FileName;
            if (Path.GetExtension(path).Equals(".zip", StringComparison.OrdinalIgnoreCase))
            {
                using var zip = ZipFile.OpenRead(path);
                var entry = zip.Entries.FirstOrDefault(e => new[] { ".bin", ".gen", ".md", ".smd" }.Contains(Path.GetExtension(e.Name).ToLowerInvariant()));
                if (entry == null) throw new InvalidDataException("O ZIP não contém uma ROM compatível.");
                path = Path.Combine(Path.GetTempPath(), "MegaDriveSolo_" + Path.GetFileName(entry.Name)); entry.ExtractToFile(path, true);
            }
            StartRom(path);
        }
        catch (Exception e) { MessageBox.Show(this, e.Message, "Não foi possível abrir o jogo", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }

    void StartRom(string path)
    {
        if (!initialized) throw new InvalidOperationException("O núcleo Genesis Plus GX não inicializou.");
        if (gameLoaded) { unloadGameCallback(); gameLoaded = false; frameTimer.Stop(); }
        var game = new retro_game_info { path = Marshal.StringToHGlobalAnsi(path), data = IntPtr.Zero, size = UIntPtr.Zero, meta = IntPtr.Zero };
        try { if (!loadGameCallback(ref game)) throw new InvalidDataException("Genesis Plus GX recusou essa ROM."); }
        finally { Marshal.FreeHGlobal(game.path); }
        gameLoaded = true; currentRom = path; screen.Controls.Clear(); video = new GameVideoView { Dock = DockStyle.Fill }; screen.Controls.Add(video); Text = "Mega Drive Solo — " + Path.GetFileName(path); frameTimer.Start(); Focus();
    }

    void ToggleFullscreen() { if (FormBorderStyle == FormBorderStyle.None) { FormBorderStyle = FormBorderStyle.Sizable; WindowState = FormWindowState.Maximized; } else { FormBorderStyle = FormBorderStyle.None; WindowState = FormWindowState.Maximized; } }
    protected override void WndProc(ref Message m)
    {
        int id = ButtonForVirtualKey((int)m.WParam);
        if (id >= 0 && (m.Msg == WM_KEYDOWN || m.Msg == WM_SYSKEYDOWN)) keyboard[id] = true;
        if (id >= 0 && (m.Msg == WM_KEYUP || m.Msg == WM_SYSKEYUP)) keyboard[id] = false;
        base.WndProc(ref m);
    }
    int ButtonForVirtualKey(int key) => key switch
    {
        0x26 => 4, 0x28 => 5, 0x25 => 6, 0x27 => 7,
        (int)Keys.Z => 0, (int)Keys.X => 1, (int)Keys.Back => 2, (int)Keys.Enter => 3,
        (int)Keys.C => 8, (int)Keys.A => 9, (int)Keys.S => 10, (int)Keys.D => 11,
        (int)Keys.Q => 12, (int)Keys.W => 13, (int)Keys.ShiftKey => 14, (int)Keys.ControlKey => 15,
        _ => -1
    };

    bool PadButton(ushort flags, ushort mask) => (flags & mask) != 0;
    short InputState(uint port, uint device, uint index, uint id)
    {
        if (port != 0 || device != 1 || id >= 16) return 0;
        bool pressed = keyboard[id];
        try
        {
            if (XInputGetState(0, out var s) == 0)
            {
                ushort f = s.gamepad.buttons;
                pressed |= id switch
                {
                    0 => PadButton(f, 0x2000), 1 => PadButton(f, 0x8000), 2 => PadButton(f, 0x0020), 3 => PadButton(f, 0x0010),
                    4 => PadButton(f, 0x0001) || s.gamepad.thumbLY > 16000, 5 => PadButton(f, 0x0002) || s.gamepad.thumbLY < -16000,
                    6 => PadButton(f, 0x0004) || s.gamepad.thumbLX < -16000, 7 => PadButton(f, 0x0008) || s.gamepad.thumbLX > 16000,
                    8 => PadButton(f, 0x1000), 9 => PadButton(f, 0x4000), 10 => PadButton(f, 0x0100), 11 => PadButton(f, 0x0200),
                    12 => s.gamepad.leftTrigger > 128, 13 => s.gamepad.rightTrigger > 128, _ => false
                };
            }
        }
        catch (DllNotFoundException) { }
        return pressed ? (short)1 : (short)0;
    }

    void ShutdownCore() { try { frameTimer.Stop(); audio?.Dispose(); if (gameLoaded) unloadGameCallback(); if (initialized) deinitCallback(); if (core != IntPtr.Zero) FreeLibrary(core); } catch { } }
    T Export<T>(string name) where T : Delegate => Marshal.GetDelegateForFunctionPointer<T>(GetProcAddress(core, name));
    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr LoadLibrary(string path);
    [DllImport("kernel32", SetLastError = true)] static extern bool FreeLibrary(IntPtr h);
    [DllImport("kernel32", CharSet = CharSet.Ansi, SetLastError = true, EntryPoint = "GetProcAddress")] static extern IntPtr GetProcAddress(IntPtr h, string name);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate bool retro_environment_t(uint cmd, IntPtr data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_video_refresh_t(IntPtr data, uint width, uint height, UIntPtr pitch);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_audio_sample_t(short left, short right);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate UIntPtr retro_audio_sample_batch_t(IntPtr data, UIntPtr frames);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_input_poll_t();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate short retro_input_state_t(uint port, uint device, uint index, uint id);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_basic_t();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_environment_t(retro_environment_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_video_refresh_t(retro_video_refresh_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_audio_sample_t(retro_audio_sample_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_audio_sample_batch_t(retro_audio_sample_batch_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_input_poll_t(retro_input_poll_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_input_state_t(retro_input_state_t cb);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate bool retro_load_game_t(ref retro_game_info info);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void retro_set_controller_port_device_t(uint port, uint device);
    bool EnvironmentCallback(uint cmd, IntPtr data)
    {
        const uint SET_PIXEL_FORMAT = 10, SET_VARIABLES = 16, SET_SUPPORT_NO_GAME = 18, SET_INPUT_DESCRIPTORS = 11;
        if (cmd == SET_PIXEL_FORMAT) { Marshal.WriteInt32(data, 1); return true; } // RETRO_PIXEL_FORMAT_XRGB8888
        if (cmd == SET_SUPPORT_NO_GAME || cmd == SET_VARIABLES || cmd == SET_INPUT_DESCRIPTORS) return true;
        return false;
    }
    void VideoRefresh(IntPtr data, uint width, uint height, UIntPtr pitch) { if (data != IntPtr.Zero && video != null) video.Present(data, (int)width, (int)height, (int)pitch.ToUInt64()); }
    void AudioSample(short left, short right) { Span<short> pair = stackalloc short[2] { left, right }; audio?.Write(pair); }
    UIntPtr AudioBatch(IntPtr data, UIntPtr frames)
    {
        int samples = checked((int)frames.ToUInt64() * 2);
        if (samples <= 0 || samples > 65536) return frames;
        short[] buffer = new short[samples]; Marshal.Copy(data, buffer, 0, samples);
        audio?.Write(buffer); return frames;
    }
    [StructLayout(LayoutKind.Sequential, Pack = 2)] struct WaveFormatEx { public ushort formatTag, channels; public uint samplesPerSec, avgBytesPerSec; public ushort blockAlign, bitsPerSample, cbSize; }
    [StructLayout(LayoutKind.Sequential)] struct WaveHeader { public IntPtr data; public uint bufferLength, bytesRecorded; public IntPtr user; public uint flags, loops; public IntPtr next, reserved; }
    [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern uint waveOutOpen(out IntPtr handle, uint device, ref WaveFormatEx format, IntPtr callback, IntPtr instance, uint flags);
    [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern uint waveOutPrepareHeader(IntPtr handle, IntPtr header, uint size);
    [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern uint waveOutWrite(IntPtr handle, IntPtr header, uint size);
    [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern uint waveOutUnprepareHeader(IntPtr handle, IntPtr header, uint size);
    [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern uint waveOutClose(IntPtr handle);
    sealed class WaveOutAudio : IDisposable
    {
        readonly IntPtr handle; readonly object sync = new(); readonly int headerSize = Marshal.SizeOf<WaveHeader>();
        readonly System.Collections.Generic.List<(IntPtr header, IntPtr data)> buffers = new();
        public WaveOutAudio()
        {
            var fmt = new WaveFormatEx { formatTag = 1, channels = 2, samplesPerSec = 44100, avgBytesPerSec = 176400, blockAlign = 4, bitsPerSample = 16, cbSize = 0 };
            if (waveOutOpen(out handle, 0xFFFFFFFF, ref fmt, IntPtr.Zero, IntPtr.Zero, 0) != 0) throw new InvalidOperationException("Não foi possível abrir o áudio do Windows.");
        }
        public void Write(ReadOnlySpan<short> samples)
        {
            if (samples.Length == 0) return;
            lock (sync)
            {
                for (int i = buffers.Count - 1; i >= 0; i--)
                {
                    var (oldHeader, oldData) = buffers[i];
                    if ((Marshal.PtrToStructure<WaveHeader>(oldHeader).flags & 1) == 0) continue;
                    waveOutUnprepareHeader(handle, oldHeader, (uint)headerSize);
                    Marshal.FreeHGlobal(oldHeader); Marshal.FreeHGlobal(oldData); buffers.RemoveAt(i);
                }
                if (buffers.Count >= 8) return;
                int bytes = samples.Length * sizeof(short); IntPtr data = Marshal.AllocHGlobal(bytes); IntPtr hdr = Marshal.AllocHGlobal(headerSize);
                Marshal.Copy(samples.ToArray(), 0, data, samples.Length);
                Marshal.StructureToPtr(new WaveHeader { data = data, bufferLength = (uint)bytes }, hdr, false);
                if (waveOutPrepareHeader(handle, hdr, (uint)headerSize) != 0 || waveOutWrite(handle, hdr, (uint)headerSize) != 0) { Marshal.FreeHGlobal(hdr); Marshal.FreeHGlobal(data); return; }
                buffers.Add((hdr, data));
            }
        }
        public void Dispose()
        {
            lock (sync)
            {
                waveOutClose(handle);
                foreach (var (header, data) in buffers) { Marshal.FreeHGlobal(header); Marshal.FreeHGlobal(data); }
                buffers.Clear();
            }
        }
    }
    static void InputPoll() { }

    [StructLayout(LayoutKind.Sequential)] struct retro_game_info { public IntPtr path, data; public UIntPtr size; public IntPtr meta; }
    sealed class GameVideoView : Control
    {
        Bitmap? bitmap;
        public GameVideoView() { DoubleBuffered = true; BackColor = Color.Black; }
        public void Present(IntPtr data, int w, int h, int pitch)
        {
            if (w <= 0 || h <= 0 || w > 2048 || h > 2048 || pitch < w * 4) return;
            var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb); var rect = new Rectangle(0, 0, w, h); var bits = bmp.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            try { unsafe { byte* src = (byte*)data; byte* dst = (byte*)bits.Scan0; for (int y = 0; y < h; y++) Buffer.MemoryCopy(src + y * pitch, dst + y * bits.Stride, bits.Stride, w * 4); } }
            finally { bmp.UnlockBits(bits); }
            var old = bitmap; bitmap = bmp; old?.Dispose(); Invalidate();
        }
        protected override void OnPaint(PaintEventArgs e) { base.OnPaint(e); if (bitmap == null) return; e.Graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor; e.Graphics.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half; e.Graphics.DrawImage(bitmap, ClientRectangle); }
        protected override void Dispose(bool disposing) { if (disposing) bitmap?.Dispose(); base.Dispose(disposing); }
    }
}
