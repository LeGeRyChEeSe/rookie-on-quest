using UnityEngine;
using UnityEngine.UI;
using System.Threading.Tasks;

namespace RookieOnQuest.UI
{
    public class GameListItem : MonoBehaviour
    {
        public Text TitleText;
        public Text VersionText;
        public Button ActionButton;
        public Text ActionButtonText;
        public Image IconImage;

        public int CurrentVirtualIndex { get; set; } = -1;
        public string PackageName { get; private set; }
        public string DownloadUrl { get; private set; }

        private System.Action<string> _onAction;
        private Coroutine _loadingCoroutine;
        private Texture2D _loadedTexture;

        public void Setup(string title, string version, string packageName, string releaseName, string iconsPath, Sprite placeholder, System.Action<string> onAction)
        {
            if (TitleText) TitleText.text = title;
            if (VersionText) VersionText.text = version;
            
            PackageName = packageName;
            _onAction = onAction;

            if (ActionButton)
            {
                ActionButton.onClick.RemoveAllListeners();
                ActionButton.onClick.AddListener(() => _onAction?.Invoke(PackageName));
            }

            if (_loadingCoroutine != null) StopCoroutine(_loadingCoroutine);
            _loadingCoroutine = StartCoroutine(LoadIconInternal(iconsPath, placeholder));
        }

        private System.Collections.IEnumerator LoadIconInternal(string iconsPath, Sprite placeholder)
        {
            Image target = IconImage;
            
            // Cleanup previous texture if it exists
            CleanupTexture();

            target.sprite = placeholder;
            target.color = new Color(0.1f, 0.1f, 0.1f, 1f);

            if (!UIManager.Instance.IsIconLoadingEnabled) yield break;

            string foundPath = UIManager.Instance.FindBestIcon(PackageName);
            
            if (foundPath != null)
            {
                byte[] data = null;
                try { data = System.IO.File.ReadAllBytes(foundPath); } catch { }

                if (data != null && data.Length > 0)
                {
                    Texture2D tex = new Texture2D(2, 2);
                    if (tex.LoadImage(data))
                    {
                        _loadedTexture = tex;
                        target.sprite = Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f));
                        target.color = Color.white;
                    }
                    else
                    {
                        Destroy(tex);
                    }
                }
            }
        }

        private void CleanupTexture()
        {
            if (IconImage && IconImage.sprite != null && _loadedTexture != null)
            {
                // If the current sprite uses the loaded texture, null it out first
                if (IconImage.sprite.texture == _loadedTexture)
                {
                    var oldSprite = IconImage.sprite;
                    IconImage.sprite = null;
                    Destroy(oldSprite);
                }
            }
            
            if (_loadedTexture != null)
            {
                Destroy(_loadedTexture);
                _loadedTexture = null;
            }
        }

        private void OnDisable()
        {
            if (_loadingCoroutine != null) StopCoroutine(_loadingCoroutine);
            _loadingCoroutine = null;
            CleanupTexture();
        }

        public void SetStatus(string status)
        {
            if (ActionButtonText) ActionButtonText.text = status;
        }
    }
}
