#!/bin/bash
# Universal worktree initialization script
# Compatible with any AI agent or human developer
# Usage: ./scripts/init-worktree.sh <story-id> [agent-name]

set -e  # Exit on error

# Color output for better visibility
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
STORY_ID="$1"
AGENT_NAME="${2:-agent}"

if [ -z "$STORY_ID" ]; then
    echo -e "${RED}Error: STORY_ID is required${NC}"
    echo "Usage: $0 <story-id> [agent-name]"
    echo "Example: $0 1-8 dev"
    exit 1
fi

# Get project root (assumes script is in <project-root>/scripts/)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKTREE_DIR="$PROJECT_ROOT/worktrees/$AGENT_NAME-story-$STORY_ID"
BRANCH_NAME="$AGENT_NAME-story-$STORY_ID"

echo -e "${GREEN}=== Git Worktree Initialization for Story $STORY_ID ===${NC}"
echo "Project Root: $PROJECT_ROOT"
echo "Worktree Path: $WORKTREE_DIR"
echo "Branch Name: $BRANCH_NAME"
echo ""

# Step 1: Check if worktree already exists
if [ -d "$WORKTREE_DIR" ]; then
    echo -e "${YELLOW}⚠️  Worktree already exists at $WORKTREE_DIR${NC}"
    echo "Options:"
    echo "  1. Use existing worktree (cd $WORKTREE_DIR)"
    echo "  2. Remove and recreate (git worktree remove $BRANCH_NAME && run this script again)"
    echo ""
    read -p "Use existing worktree? (y/n): " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${GREEN}✅ Using existing worktree${NC}"
        echo "Worktree location: $WORKTREE_DIR"
        echo "Story ID: $(cat $WORKTREE_DIR/.story-id 2>/dev/null || echo 'Not found')"
        exit 0
    else
        echo -e "${RED}Aborted. Please remove existing worktree first.${NC}"
        exit 1
    fi
fi

# Step 2: Create worktree and branch
echo -e "${YELLOW}📂 Creating git worktree...${NC}"
cd "$PROJECT_ROOT"
git worktree add "$WORKTREE_DIR" -b "$BRANCH_NAME"

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Failed to create worktree${NC}"
    echo "Possible causes:"
    echo "  - Branch '$BRANCH_NAME' already exists"
    echo "  - Worktree path '$WORKTREE_DIR' already exists"
    echo ""
    echo "Fix:"
    echo "  git worktree remove $BRANCH_NAME 2>/dev/null || true"
    echo "  git branch -D $BRANCH_NAME 2>/dev/null || true"
    exit 1
fi

echo -e "${GREEN}✅ Worktree created successfully${NC}"

# Step 3: Create .story-id file in worktree
echo -e "${YELLOW}📝 Creating .story-id file...${NC}"
echo "$STORY_ID" > "$WORKTREE_DIR/.story-id"
echo -e "${GREEN}✅ Story ID set to: $STORY_ID${NC}"

# Step 4: Create .story-files manifest
echo -e "${YELLOW}📋 Creating .story-files manifest...${NC}"
cat > "$WORKTREE_DIR/.story-files" << EOF
# Story Files Manifest
# This file tracks which files are modified by this story to prevent conflicts
storyId: "$STORY_ID"
storyTitle: "Auto-generated - update from story file"
storyFiles:
  # Add file patterns here as you work (e.g., app/src/main/java/**/*.kt)
modifiedDuringStory: []
EOF
echo -e "${GREEN}✅ .story-files manifest created${NC}"

# Step 5: Verify setup
echo ""
echo -e "${GREEN}=== Setup Complete ===${NC}"
echo ""
echo "📍 Worktree Location: $WORKTREE_DIR"
echo "🌿 Branch Name: $BRANCH_NAME"
echo "🆔 Story ID: $(cat $WORKTREE_DIR/.story-id)"
echo ""
echo -e "${YELLOW}Next Steps for ANY Agent:${NC}"
echo "  1. Navigate to worktree: cd $WORKTREE_DIR"
echo "  2. Verify story ID: cat .story-id"
echo "  3. Start development (agent will auto-detect story from .story-id)"
echo ""
echo -e "${YELLOW}For Developers:${NC}"
echo "  - Open IDE/Editor at: $WORKTREE_DIR"
echo "  - Update .story-files as you modify files"
echo "  - When done: push branch and create PR"
echo ""
echo -e "${GREEN}✨ Ready for development!${NC}"
