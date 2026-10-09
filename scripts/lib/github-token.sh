# Sourced by the AniMusic release scripts. Sets TOKEN from $GITHUB_TOKEN, or
# from the credential file kept for this checkout outside the repo
# (~/.config/animusic/git-credentials, a git-credential-store line). Never
# prints it and never writes it anywhere.
TOKEN="${GITHUB_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$HOME/.config/animusic/git-credentials" ]]; then
    TOKEN="$(sed -E 's#https://[^:]+:([^@]+)@.*#\1#' "$HOME/.config/animusic/git-credentials" | head -1)"
fi
[[ -n "$TOKEN" ]] || { echo "no GitHub token: set GITHUB_TOKEN" >&2; exit 1; }
