using UnityEngine;
using UnityEngine.UI;
using UnityEngine.EventSystems;
using System.Collections.Generic;
using System.Linq;
using System.IO;
using System.Collections;
using System.Threading.Tasks;
using RookieOnQuest.Data;
using RookieOnQuest.Logic;

namespace RookieOnQuest.UI
{
    public class UIManager : MonoBehaviour
    {
        public static UIManager Instance;

        public Canvas MainCanvas;
        public RectTransform ContentRoot;
        public GameObject ProgressPanel;
        public Slider ProgressBar;
        public Text ProgressText;
        public InputField SearchBar;
        
        public bool IsIconLoadingEnabled { get; private set; }
        private Text _toggleBtnText;
        private Text _iconStatusText; // Restored
        private AudioSource _audioSource;
        private ScrollRect _scrollRect;
        private List<GameData> _allGames = new List<GameData>();
        private List<GameData> _currentFilteredGames = new List<GameData>();
        private System.Action<string> _onInstallRequested;
        private string _iconsPath;
        
        private RectTransform _spinnerRingOuter;
        private RectTransform _spinnerRingInner;
        private Sprite _circleSprite;

        private List<GameListItem> _pooledItems = new List<GameListItem>();
        private Coroutine _searchCoroutine;
        private Dictionary<string, string> _iconCache = new Dictionary<string, string>();
        private Queue<System.Action> _loadQueue = new Queue<System.Action>();

        // Virtual Scroll Constants
        private const float ItemHeight = 140f;
        private const float ItemSpacing = 20f;
        private const float ItemFullHeight = ItemHeight + ItemSpacing;
        private const int VisibleBuffer = 3;

        private void Awake()
        {
            Instance = this;
            Application.runInBackground = true;
            _iconsPath = Path.Combine(Application.persistentDataPath, "meta_extracted");
            _circleSprite = CreateCircleSprite(256);
            
            IsIconLoadingEnabled = PlayerPrefs.GetInt("IconsEnabled", 1) == 1;
            _audioSource = gameObject.AddComponent<AudioSource>();
            
            // Ensure there's an AudioListener in the scene to hear sounds
            if (Object.FindFirstObjectByType<AudioListener>() == null)
            {
                gameObject.AddComponent<AudioListener>();
            }
            
            CreateUI();
            
            if (_scrollRect != null)
            {
                _scrollRect.onValueChanged.AddListener((pos) => UpdateVirtualScroll());
            }

            StartCoroutine(ProcessLoadQueue());
        }

        private IEnumerator ProcessLoadQueue()
        {
            while (true)
            {
                if (_loadQueue.Count > 0)
                {
                    _loadQueue.Dequeue().Invoke();
                    yield return null;
                }
                else yield return null;
            }
        }

        public void EnqueueLoad(System.Action action)
        {
            _loadQueue.Enqueue(action);
        }

        private void OnIconToggleClicked()
        {
            IsIconLoadingEnabled = !IsIconLoadingEnabled;
            PlayerPrefs.SetInt("IconsEnabled", IsIconLoadingEnabled ? 1 : 0);
            PlayerPrefs.Save();
            
            if (_toggleBtnText) _toggleBtnText.text = IsIconLoadingEnabled ? "IMAGES: ON" : "IMAGES: OFF";

            foreach(var item in _pooledItems) {
                if (item.gameObject.activeSelf) {
                    var game = _currentFilteredGames[item.CurrentVirtualIndex];
                    item.Setup(game.GameName, string.Format("v{0} | {1}", game.VersionCode, game.PackageName), game.PackageName, game.ReleaseName, _iconsPath, _circleSprite, _onInstallRequested);
                }
            }
        }

        public void UpdateIconList()
        {
            if (Directory.Exists(_iconsPath))
            {
                Task.Run(() => {
                    var files = Directory.GetFiles(_iconsPath, "*.*")
                        .Where(s => s.EndsWith(".jpg") || s.EndsWith(".png"))
                        .ToArray();
                    
                    var newCache = new Dictionary<string, string>();
                    foreach(var f in files) {
                        string key = Path.GetFileNameWithoutExtension(f).ToLower();
                        if (!newCache.ContainsKey(key)) newCache.Add(key, f);
                    }
                    _iconCache = newCache;

                    // Update UI Status and refresh visible items on main thread
                    UnityMainThreadDispatcher.Instance().Enqueue(() => {
                        if (_iconStatusText != null)
                            _iconStatusText.text = string.Format("Icons ready: {0}", _iconCache.Count);
                        
                        UpdateVirtualScroll();
                    });
                });
            }
        }

        public string FindBestIcon(string packageName)
        {
            if (_iconCache == null || _iconCache.Count == 0) return null;
            string search = packageName.ToLower();
            if (_iconCache.TryGetValue(search, out string path)) return path;
            return null;
        }

        private void Update()
        {
            AnimateModernLoading();
        }

        private void AnimateModernLoading()
        {
            if (_spinnerRingOuter != null && ProgressPanel.activeInHierarchy)
            {
                _spinnerRingOuter.Rotate(0, 0, -200 * Time.deltaTime);
                _spinnerRingInner.Rotate(0, 0, 350 * Time.deltaTime);
                float pulse = 0.85f + Mathf.PingPong(Time.time * 0.6f, 0.15f);
                _spinnerRingOuter.localScale = new Vector3(pulse, pulse, 1);
            }
        }

        private Sprite CreateCircleSprite(int size)
        {
            Texture2D tex = new Texture2D(size, size, TextureFormat.RGBA32, false);
            float center = size / 2f;
            float radius = size / 2f - 2;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dist = Vector2.Distance(new Vector2(x, y), new Vector2(center, center));
                    tex.SetPixel(x, y, new Color(1, 1, 1, dist <= radius ? 1 : 0));
                }
            }
            tex.Apply();
            return Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f));
        }

        private void CreateUI()
        {
            if (FindFirstObjectByType<EventSystem>() == null)
            {
                GameObject esObj = new GameObject("EventSystem");
                esObj.AddComponent<EventSystem>();
                esObj.AddComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>();
            }

            GameObject canvasObj = new GameObject("Canvas");
            MainCanvas = canvasObj.AddComponent<Canvas>();
            MainCanvas.renderMode = RenderMode.ScreenSpaceOverlay;
            CanvasScaler scaler = canvasObj.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920, 1080);
            canvasObj.AddComponent<GraphicRaycaster>();

            Font uiFont = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            if (uiFont == null) uiFont = Resources.FindObjectsOfTypeAll<Font>().FirstOrDefault();

            GameObject rootObj = new GameObject("RootPanel");
            rootObj.transform.SetParent(canvasObj.transform, false);
            rootObj.AddComponent<Image>().color = new Color(0.02f, 0.02f, 0.03f);
            RectTransform rootRect = rootObj.GetComponent<RectTransform>();
            rootRect.anchorMin = Vector2.zero; rootRect.anchorMax = Vector2.one;
            rootRect.offsetMin = Vector2.zero; rootRect.offsetMax = Vector2.zero;

            // 1. View Area
            GameObject viewArea = new GameObject("ViewArea");
            viewArea.transform.SetParent(rootObj.transform, false);
            RectTransform vaRect = viewArea.AddComponent<RectTransform>();
            vaRect.anchorMin = Vector2.zero; vaRect.anchorMax = Vector2.one;
            vaRect.offsetMin = new Vector2(180, 50);
            vaRect.offsetMax = new Vector2(-150, -280);

            // ScrollView
            GameObject svObj = new GameObject("GameScrollView");
            svObj.transform.SetParent(viewArea.transform, false);
            
            // Capture raycasts even on empty areas for scrolling
            var svBg = svObj.AddComponent<Image>();
            svBg.color = new Color(0, 0, 0, 0); // Transparent
            svBg.raycastTarget = true;

            _scrollRect = svObj.AddComponent<ScrollRect>();
            RectTransform svRect = svObj.GetComponent<RectTransform>();
            svRect.anchorMin = Vector2.zero; svRect.anchorMax = Vector2.one;
            svRect.offsetMin = Vector2.zero; svRect.offsetMax = Vector2.zero;
            _scrollRect.horizontal = false; _scrollRect.vertical = true;
            _scrollRect.scrollSensitivity = 150f; // More sensitive for VR
            _scrollRect.inertia = true;
            _scrollRect.decelerationRate = 0.05f; // Much smoother momentum (iOS-like)
            
            GameObject vpObj = new GameObject("Viewport");
            vpObj.transform.SetParent(svObj.transform, false);
            vpObj.AddComponent<RectMask2D>(); 
            RectTransform vpRect = vpObj.GetComponent<RectTransform>();
            vpRect.anchorMin = Vector2.zero; vpRect.anchorMax = Vector2.one;
            vpRect.offsetMin = Vector2.zero; vpRect.offsetMax = Vector2.zero;
            _scrollRect.viewport = vpRect;

            GameObject contentObj = new GameObject("Content");
            contentObj.transform.SetParent(vpObj.transform, false);
            ContentRoot = contentObj.AddComponent<RectTransform>();
            ContentRoot.anchorMin = new Vector2(0, 1); ContentRoot.anchorMax = new Vector2(1, 1);
            ContentRoot.pivot = new Vector2(0.5f, 1);
            _scrollRect.content = ContentRoot;

            // Scrollbar (Separated)
            GameObject sbObj = new GameObject("Scrollbar");
            sbObj.transform.SetParent(rootObj.transform, false);
            Scrollbar sb = sbObj.AddComponent<Scrollbar>();
            sb.direction = Scrollbar.Direction.BottomToTop;
            RectTransform sbRect = sbObj.GetComponent<RectTransform>();
            sbRect.anchorMin = new Vector2(1, 0); sbRect.anchorMax = new Vector2(1, 1);
            sbRect.pivot = new Vector2(1, 0.5f);
            sbRect.sizeDelta = new Vector2(40, -350); 
            sbRect.anchoredPosition = new Vector2(-50, -140);
            sbObj.AddComponent<Image>().color = new Color(1, 1, 1, 0.05f);
            GameObject handleObj = new GameObject("Handle");
            handleObj.transform.SetParent(sbObj.transform, false);
            handleObj.AddComponent<Image>().color = new Color(0.2f, 0.7f, 1f, 0.6f);
            sb.handleRect = handleObj.GetComponent<RectTransform>();
            _scrollRect.verticalScrollbar = sb;

            // Alphabet Index Bar
            CreateAlphabetIndex(rootObj, uiFont);

            // 2. Top Bar
            GameObject topBar = new GameObject("TopBar");
            topBar.transform.SetParent(rootObj.transform, false);
            topBar.AddComponent<Image>().color = new Color(0.05f, 0.05f, 0.08f);
            RectTransform topBarRect = topBar.GetComponent<RectTransform>();
            topBarRect.anchorMin = new Vector2(0, 1); topBarRect.anchorMax = new Vector2(1, 1);
            topBarRect.pivot = new Vector2(0.5f, 1);
            topBarRect.sizeDelta = new Vector2(0, 280);
            topBarRect.anchoredPosition = Vector2.zero;

            GameObject titleObj = new GameObject("Title");
            titleObj.transform.SetParent(topBar.transform, false);
            Text titleText = titleObj.AddComponent<Text>();
            titleText.font = uiFont; titleText.text = "ROOKIE ON QUEST";
            titleText.fontSize = 52; titleText.fontStyle = FontStyle.Bold;
            titleText.color = new Color(0.2f, 0.7f, 1f);
            titleText.alignment = TextAnchor.MiddleCenter;
            RectTransform titleRect = titleObj.GetComponent<RectTransform>();
            titleRect.anchorMin = new Vector2(0, 0.5f); titleRect.anchorMax = new Vector2(1, 1);
            titleRect.offsetMin = new Vector2(0, 20); titleRect.offsetMax = Vector2.zero;

            GameObject searchObj = new GameObject("SearchBar");
            searchObj.transform.SetParent(topBar.transform, false);
            searchObj.AddComponent<Image>().color = new Color(0.1f, 0.1f, 0.15f);
            SearchBar = searchObj.AddComponent<InputField>();
            RectTransform searchRect = searchObj.GetComponent<RectTransform>();
            searchRect.anchorMin = new Vector2(0, 0); searchRect.anchorMax = new Vector2(0.7f, 0.5f);
            searchRect.offsetMin = new Vector2(100, 40); searchRect.offsetMax = new Vector2(-20, -40);
            
            GameObject searchTextObj = new GameObject("Text");
            searchTextObj.transform.SetParent(searchObj.transform, false);
            Text searchText = searchTextObj.AddComponent<Text>();
            searchText.font = uiFont; searchText.fontSize = 32; searchText.color = Color.white;
            searchText.alignment = TextAnchor.MiddleLeft;
            RectTransform stRect = searchTextObj.GetComponent<RectTransform>();
            stRect.anchorMin = Vector2.zero; stRect.anchorMax = Vector2.one;
            stRect.offsetMin = new Vector2(50, 0); stRect.offsetMax = new Vector2(-50, 0);
            SearchBar.textComponent = searchText;

            GameObject placeholderObj = new GameObject("Placeholder");
            placeholderObj.transform.SetParent(searchObj.transform, false);
            Text placeholderText = placeholderObj.AddComponent<Text>();
            placeholderText.font = uiFont; placeholderText.fontSize = 32; placeholderText.color = Color.gray;
            placeholderText.text = "Search 2400+ games..."; placeholderText.alignment = TextAnchor.MiddleLeft;
            RectTransform phRect = placeholderObj.GetComponent<RectTransform>();
            phRect.anchorMin = Vector2.zero; phRect.anchorMax = Vector2.one;
            phRect.offsetMin = new Vector2(50, 0); phRect.offsetMax = new Vector2(-50, 0);
            SearchBar.placeholder = placeholderText;
            SearchBar.onValueChanged.AddListener(OnSearchChanged);

            // Icon Toggle
            GameObject toggleBtnObj = new GameObject("IconToggle");
            toggleBtnObj.transform.SetParent(topBar.transform, false);
            Button toggleBtn = toggleBtnObj.AddComponent<Button>();
            toggleBtnObj.AddComponent<Image>().color = new Color(0.2f, 0.2f, 0.3f);
            RectTransform toggleRect = toggleBtnObj.GetComponent<RectTransform>();
            toggleRect.anchorMin = new Vector2(0.85f, 0); toggleRect.anchorMax = new Vector2(1, 0.5f); // Shifted right
            toggleRect.offsetMin = new Vector2(10, 40); toggleRect.offsetMax = new Vector2(-20, -40);
            
            // ... (rest of toggle setup)
            GameObject toggleTextObj = new GameObject("Text");
            toggleTextObj.transform.SetParent(toggleBtnObj.transform, false);
            _toggleBtnText = toggleTextObj.AddComponent<Text>();
            _toggleBtnText.font = uiFont; _toggleBtnText.fontSize = 24; _toggleBtnText.alignment = TextAnchor.MiddleCenter;
            _toggleBtnText.color = Color.white; 
            _toggleBtnText.text = IsIconLoadingEnabled ? "IMAGES: ON" : "IMAGES: OFF";
            RectTransform ttr = toggleTextObj.GetComponent<RectTransform>();
            ttr.anchorMin = Vector2.zero; ttr.anchorMax = Vector2.one;
            toggleBtn.onClick.AddListener(OnIconToggleClicked);

            // Refresh Button (New)
            GameObject refreshBtnObj = new GameObject("RefreshBtn");
            refreshBtnObj.transform.SetParent(topBar.transform, false);
            Button refreshBtn = refreshBtnObj.AddComponent<Button>();
            refreshBtnObj.AddComponent<Image>().color = new Color(0.15f, 0.4f, 0.15f);
            RectTransform refreshRect = refreshBtnObj.GetComponent<RectTransform>();
            refreshRect.anchorMin = new Vector2(0.7f, 0); refreshRect.anchorMax = new Vector2(0.85f, 0.5f);
            refreshRect.offsetMin = new Vector2(10, 40); refreshRect.offsetMax = new Vector2(-10, -40);

            GameObject refreshTextObj = new GameObject("Text");
            refreshTextObj.transform.SetParent(refreshBtnObj.transform, false);
            Text refreshText = refreshTextObj.AddComponent<Text>();
            refreshText.font = uiFont; refreshText.fontSize = 24; refreshText.alignment = TextAnchor.MiddleCenter;
            refreshText.color = Color.white; refreshText.text = "REFRESH";
            RectTransform rtr = refreshTextObj.GetComponent<RectTransform>();
            rtr.anchorMin = Vector2.zero; rtr.anchorMax = Vector2.one;
            refreshBtn.onClick.AddListener(() => GameManager.Instance.RefreshCatalog());

            // Icon Status Text (Restored)
            GameObject statusObj = new GameObject("IconStatus");
            statusObj.transform.SetParent(rootObj.transform, false);
            _iconStatusText = statusObj.AddComponent<Text>();
            _iconStatusText.font = uiFont; _iconStatusText.fontSize = 20;
            _iconStatusText.color = new Color(1, 1, 1, 0.4f);
            _iconStatusText.alignment = TextAnchor.LowerRight;
            _iconStatusText.text = "Icons ready: 0";
            RectTransform statusRect = _iconStatusText.GetComponent<RectTransform>();
            statusRect.anchorMin = new Vector2(1, 0); statusRect.anchorMax = new Vector2(1, 0);
            statusRect.pivot = new Vector2(1, 0);
            statusRect.anchoredPosition = new Vector2(-20, 10);
            statusRect.sizeDelta = new Vector2(400, 40);

            // Progress Overlay
            ProgressPanel = new GameObject("ProgressOverlay");
            ProgressPanel.transform.SetParent(canvasObj.transform, false);
            ProgressPanel.AddComponent<Image>().color = new Color(0, 0, 0, 0.99f);
            RectTransform ppRect = ProgressPanel.GetComponent<RectTransform>();
            ppRect.anchorMin = Vector2.zero; ppRect.anchorMax = Vector2.one;
            GameObject loaderRoot = new GameObject("LoaderCenter");
            loaderRoot.transform.SetParent(ProgressPanel.transform, false);
            RectTransform lRootRect = loaderRoot.AddComponent<RectTransform>();
            lRootRect.sizeDelta = new Vector2(400, 400);
            lRootRect.anchoredPosition = new Vector2(0, 180);
            _spinnerRingOuter = CreateModernRing(loaderRoot, 300, new Color(0.2f, 0.7f, 1f, 0.3f), 0.75f);
            _spinnerRingInner = CreateModernRing(loaderRoot, 220, new Color(0.3f, 0.9f, 1f, 0.8f), 0.25f);
            GameObject pTextObj = new GameObject("Msg");
            pTextObj.transform.SetParent(ProgressPanel.transform, false);
            ProgressText = pTextObj.AddComponent<Text>();
            ProgressText.font = uiFont; ProgressText.fontSize = 42;
            ProgressText.alignment = TextAnchor.MiddleCenter; ProgressText.color = Color.white;
            RectTransform ptRect = pTextObj.GetComponent<RectTransform>();
            ptRect.anchorMin = new Vector2(0, 0.3f); ptRect.anchorMax = new Vector2(1, 0.5f);
            GameObject sliderObj = new GameObject("Bar");
            sliderObj.transform.SetParent(ProgressPanel.transform, false);
            ProgressBar = sliderObj.AddComponent<Slider>();
            RectTransform slRect = sliderObj.GetComponent<RectTransform>();
            slRect.anchorMin = new Vector2(0.25f, 0.25f); slRect.anchorMax = new Vector2(0.75f, 0.27f);
            GameObject fill = new GameObject("Fill");
            fill.transform.SetParent(sliderObj.transform, false);
            fill.AddComponent<Image>().color = new Color(0.2f, 0.8f, 1f);
            ProgressBar.fillRect = fill.GetComponent<RectTransform>();
            ProgressBar.minValue = 0; ProgressBar.maxValue = 1;

            ProgressPanel.SetActive(false);
        }

        private void CreateAlphabetIndex(GameObject parent, Font font)
        {
            GameObject indexObj = new GameObject("AlphabetIndex");
            indexObj.transform.SetParent(parent.transform, false);
            RectTransform rect = indexObj.AddComponent<RectTransform>();
            rect.anchorMin = new Vector2(0, 0); rect.anchorMax = new Vector2(0, 1);
            rect.pivot = new Vector2(0, 0.5f);
            rect.sizeDelta = new Vector2(120, -400);
            rect.anchoredPosition = new Vector2(30, -140);

            VerticalLayoutGroup vlg = indexObj.AddComponent<VerticalLayoutGroup>();
            vlg.childControlHeight = true; vlg.childForceExpandHeight = true;
            vlg.spacing = 2;

            string alpha = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            foreach (char c in alpha)
            {
                GameObject btnObj = new GameObject(c.ToString());
                btnObj.transform.SetParent(indexObj.transform, false);
                Button btn = btnObj.AddComponent<Button>();
                Text t = btnObj.AddComponent<Text>();
                t.font = font; t.text = c.ToString();
                t.fontSize = 28; t.alignment = TextAnchor.MiddleCenter;
                t.color = new Color(0.4f, 0.8f, 1f);
                char letter = c;
                btn.onClick.AddListener(() => JumpToLetter(letter));
            }
        }

        private void JumpToLetter(char letter)
        {
            if (_allGames == null || _allGames.Count == 0 || _scrollRect == null) return;
            int index = -1;
            if (letter == '#') index = _currentFilteredGames.FindIndex(g => !char.IsLetter(g.GameName[0]));
            else index = _currentFilteredGames.FindIndex(g => g.GameName.ToUpper().StartsWith(letter.ToString()));

            if (index != -1)
            {
                float totalHeight = Mathf.Max(1, (_currentFilteredGames.Count * ItemFullHeight) - _scrollRect.viewport.rect.height);
                float targetY = index * ItemFullHeight;
                _scrollRect.verticalNormalizedPosition = Mathf.Clamp01(1f - (targetY / totalHeight));
                UpdateVirtualScroll();
            }
        }

        private RectTransform CreateModernRing(GameObject parent, float size, Color color, float fill)
        {
            GameObject ring = new GameObject("Ring");
            ring.transform.SetParent(parent.transform, false);
            Image img = ring.AddComponent<Image>();
            img.sprite = _circleSprite;
            img.color = color;
            img.type = Image.Type.Filled;
            img.fillMethod = Image.FillMethod.Radial360;
            img.fillAmount = fill;
            RectTransform r = ring.GetComponent<RectTransform>();
            r.sizeDelta = new Vector2(size, size);
            GameObject mask = new GameObject("Mask");
            mask.transform.SetParent(ring.transform, false);
            mask.AddComponent<Image>().color = new Color(0, 0, 0, 1f);
            mask.GetComponent<Image>().sprite = _circleSprite;
            mask.GetComponent<RectTransform>().sizeDelta = new Vector2(size - 24, size - 24);
            return r;
        }

        private void OnSearchChanged(string query)
        {
            if (_searchCoroutine != null) StopCoroutine(_searchCoroutine);
            _searchCoroutine = StartCoroutine(SearchDebounceRoutine(query));
        }

        private IEnumerator SearchDebounceRoutine(string query)
        {
            yield return new WaitForSeconds(0.4f);
            _currentFilteredGames = string.IsNullOrEmpty(query) ? 
                _allGames : 
                _allGames.Where(g => g.GameName.ToLower().Contains(query.ToLower()) || g.PackageName.ToLower().Contains(query.ToLower())).ToList();
            
            // Force all items to be re-setup by deactivating them
            foreach(var item in _pooledItems) item.gameObject.SetActive(false);

            ResetScroll();
            UpdateVirtualScroll();
        }

        public void PopulateList(List<GameData> games, System.Action<string> onInstallClick)
        {
            _allGames = games;
            _currentFilteredGames = games;
            _onInstallRequested = onInstallClick;
            
            // Force all items to be re-setup by deactivating them
            foreach(var item in _pooledItems) item.gameObject.SetActive(false);

            ResetScroll();
            UpdateVirtualScroll();
        }

        private void ResetScroll()
        {
            if (_scrollRect != null) _scrollRect.verticalNormalizedPosition = 1f;
            if (ContentRoot != null && _currentFilteredGames != null)
                ContentRoot.sizeDelta = new Vector2(0, _currentFilteredGames.Count * ItemFullHeight);
        }

        private void UpdateVirtualScroll()
        {
            if (ContentRoot == null || _currentFilteredGames == null) return;

            float viewportHeight = _scrollRect.viewport.rect.height;
            float scrollPos = ContentRoot.anchoredPosition.y;
            
            int firstVisibleIndex = Mathf.FloorToInt(Mathf.Max(0, scrollPos) / ItemFullHeight);
            int visibleCount = Mathf.CeilToInt(viewportHeight / ItemFullHeight);
            
            int startIdx = Mathf.Max(0, firstVisibleIndex - VisibleBuffer);
            int endIdx = Mathf.Min(_currentFilteredGames.Count - 1, startIdx + visibleCount + (VisibleBuffer * 2));

            // If we have no games, endIdx will be -1, which is fine, the loop below will just deactivate everything
            if (_currentFilteredGames.Count == 0) endIdx = -1;

            Dictionary<int, GameListItem> activeInThisUpdate = new Dictionary<int, GameListItem>();
            
            foreach(var item in _pooledItems) {
                if (item.gameObject.activeSelf && item.CurrentVirtualIndex >= startIdx && item.CurrentVirtualIndex <= endIdx) {
                    activeInThisUpdate[item.CurrentVirtualIndex] = item;
                } else {
                    item.gameObject.SetActive(false);
                }
            }

            for (int i = startIdx; i <= endIdx; i++)
            {
                if (activeInThisUpdate.ContainsKey(i)) continue;

                GameListItem item = _pooledItems.FirstOrDefault(p => !p.gameObject.activeSelf);
                if (item == null) {
                    item = CreateListItemPrefab().GetComponent<GameListItem>();
                    _pooledItems.Add(item);
                }

                item.gameObject.SetActive(true);
                var game = _currentFilteredGames[i];
                item.Setup(game.GameName, string.Format("v{0} | {1}", game.VersionCode, game.PackageName), game.PackageName, game.ReleaseName, _iconsPath, _circleSprite, _onInstallRequested);
                item.CurrentVirtualIndex = i;
                
                RectTransform rt = item.GetComponent<RectTransform>();
                rt.anchoredPosition = new Vector2(0, -i * ItemFullHeight - (ItemSpacing/2f) - 10f);
                activeInThisUpdate[i] = item;
            }
        }

        private GameObject CreateListItemPrefab()
        {
            Font uiFont = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            if (uiFont == null) uiFont = Resources.FindObjectsOfTypeAll<Font>().FirstOrDefault();

            GameObject itemObj = new GameObject("GameItemRow");
            RectTransform rt = itemObj.AddComponent<RectTransform>();
            rt.transform.SetParent(ContentRoot, false);
            rt.anchorMin = new Vector2(0, 1); rt.anchorMax = new Vector2(1, 1);
            rt.pivot = new Vector2(0.5f, 1);
            rt.sizeDelta = new Vector2(0, ItemHeight);
            itemObj.AddComponent<Image>().color = new Color(0.08f, 0.08f, 0.12f);

            HorizontalLayoutGroup hlg = itemObj.AddComponent<HorizontalLayoutGroup>();
            hlg.padding = new RectOffset(20, 20, 15, 15); hlg.spacing = 25;
            hlg.childAlignment = TextAnchor.MiddleLeft;
            hlg.childControlWidth = true; hlg.childControlHeight = true;
            hlg.childForceExpandWidth = false; hlg.childForceExpandHeight = false;

            GameObject iconObj = new GameObject("Icon");
            iconObj.transform.SetParent(itemObj.transform, false);
            Image iconImg = iconObj.AddComponent<Image>();
            iconImg.sprite = _circleSprite;
            iconImg.color = new Color(0.02f, 0.02f, 0.02f); 
            LayoutElement iconLe = iconObj.AddComponent<LayoutElement>();
            iconLe.preferredWidth = 110; iconLe.preferredHeight = 110;

            GameObject textGroup = new GameObject("TextGroup");
            textGroup.transform.SetParent(itemObj.transform, false);
            VerticalLayoutGroup vlg = textGroup.AddComponent<VerticalLayoutGroup>();
            vlg.spacing = 2; vlg.childControlWidth = true; vlg.childControlHeight = true;
            LayoutElement textLe = textGroup.AddComponent<LayoutElement>();
            textLe.flexibleWidth = 1;

            GameObject titleObj = new GameObject("Title");
            titleObj.transform.SetParent(textGroup.transform, false);
            Text titleText = titleObj.AddComponent<Text>();
            titleText.font = uiFont; titleText.fontSize = 34; titleText.fontStyle = FontStyle.Bold;
            titleText.color = Color.white; titleText.alignment = TextAnchor.MiddleLeft;

            GameObject infoObj = new GameObject("Info");
            infoObj.transform.SetParent(textGroup.transform, false);
            Text infoText = infoObj.AddComponent<Text>();
            infoText.font = uiFont; infoText.fontSize = 22;
            infoText.color = new Color(0.6f, 0.6f, 0.7f); infoText.alignment = TextAnchor.MiddleLeft;

            GameObject btnObj = new GameObject("Btn");
            btnObj.transform.SetParent(itemObj.transform, false);
            btnObj.AddComponent<Image>().color = new Color(0.15f, 0.45f, 0.85f);
            Button btn = btnObj.AddComponent<Button>();
            LayoutElement btnLe = btnObj.AddComponent<LayoutElement>();
            btnLe.preferredWidth = 250; btnLe.preferredHeight = 85;

            GameObject btnTextObj = new GameObject("Text");
            btnTextObj.transform.SetParent(btnObj.transform, false);
            Text bText = btnTextObj.AddComponent<Text>();
            bText.font = uiFont; bText.text = "INSTALL"; bText.fontSize = 28; bText.fontStyle = FontStyle.Bold;
            bText.color = Color.white; bText.alignment = TextAnchor.MiddleCenter;
            RectTransform btr = btnTextObj.GetComponent<RectTransform>();
            btr.anchorMin = Vector2.zero; btr.anchorMax = Vector2.one;

            GameListItem item = itemObj.AddComponent<GameListItem>();
            item.TitleText = titleText;
            item.VersionText = infoText;
            item.ActionButton = btn;
            item.ActionButtonText = bText;
            item.IconImage = iconImg;

            return itemObj;
        }

        public void PlayNotificationSound()
        {
            if (_audioSource != null) _audioSource.PlayOneShot(CreateNotificationClip());
        }

        private AudioClip CreateNotificationClip()
        {
            int samplerate = 44100;
            float frequency = 880f; float duration = 0.3f;
            AudioClip myClip = AudioClip.Create("NotificationPing", samplerate, 1, samplerate, false);
            float[] samples = new float[samplerate];
            for (int i = 0; i < samples.Length; i++) {
                float time = (float)i / samplerate;
                if (time < duration) samples[i] = Mathf.Sin(2 * Mathf.PI * frequency * time) * Mathf.Exp(-time * 10f);
                else samples[i] = 0;
            }
            myClip.SetData(samples, 0);
            return myClip;
        }

        public void ShowProgress(string message, float progress)
        {
            if (ProgressPanel == null) return;
            ProgressPanel.SetActive(true);
            if (ProgressBar) ProgressBar.gameObject.SetActive(progress > 0 && progress < 0.99f);
            if (ProgressText) ProgressText.text = message;
            if (progress > 0 && progress < 0.99f && ProgressText) 
                ProgressText.text = string.Format("{0}\n{1:F0}%", message, progress * 100);
            if (ProgressBar) ProgressBar.value = progress;
        }

        public void HideProgress() { if (ProgressPanel != null) ProgressPanel.SetActive(false); }
    }
}
