--!strict
--[[
    Nish12345678910111 — White Room Corner Cutscene (0:00 to 3:12)
    Target User: nish12345678910111 (DisplayName: nis0tm | UserId: 3762390414)
    Duration: 192.0 seconds (0:00 -> 3:12)
    Sequence:
      1. Builds a minimalist, completely white room corner in Workspace.
      2. Spawns nish12345678910111 (UserId 3762390414) with authentic R15 avatar assets
         and seats the character in the 90-degree corner of the white room.
      3. Locks Workspace.CurrentCamera on the character while slowly zooming in
         continuously from 0:00 (0s) to 3:12 (192s).
      4. Gradually fades the entire screen to pure black at the end (2:55 -> 3:12).
]]

local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local Lighting = game:GetService("Lighting")
local CoreGui = game:GetService("CoreGui")

local TARGET_USERNAME = "nish12345678910111"
local TARGET_USER_ID = 3762390414
local TOTAL_DURATION = 192.0 -- 3 minutes 12 seconds (0:00 to 3:12)
local FADE_START_TIME = 175.0 -- Start gradual fade to black at 2:55, full black by 3:12

-- Clean up any previous cutscene instance
local existingScene = workspace:FindFirstChild("NishWhiteRoomCutscene")
if existingScene then
    existingScene:Destroy()
end

local sceneFolder = Instance.new("Folder")
sceneFolder.Name = "NishWhiteRoomCutscene"
sceneFolder.Parent = workspace

-- 1. Build the Completely White Room Corner
local ROOM_ORIGIN = Vector3.new(0, 500, 0) -- High altitude to avoid map interference
local ROOM_SIZE = 60
local WALL_THICKNESS = 2

local function createWhiteSurface(name: string, size: Vector3, cframe: CFrame): Part
    local part = Instance.new("Part")
    part.Name = name
    part.Size = size
    part.CFrame = cframe
    part.Anchored = true
    part.CanCollide = true
    part.Color = Color3.fromRGB(252, 252, 252)
    part.Material = Enum.Material.SmoothPlastic
    part.TopSurface = Enum.SurfaceType.Smooth
    part.BottomSurface = Enum.SurfaceType.Smooth
    part.Reflectance = 0.02
    part.Parent = sceneFolder
    return part
end

-- Corner apex is at ROOM_ORIGIN (X = 0, Y = 500, Z = 0)
-- Floor extends +X and +Z from the corner
createWhiteSurface(
    "WhiteFloor",
    Vector3.new(ROOM_SIZE, WALL_THICKNESS, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, -WALL_THICKNESS / 2, ROOM_SIZE / 2))
)

-- Ceiling
createWhiteSurface(
    "WhiteCeiling",
    Vector3.new(ROOM_SIZE, WALL_THICKNESS, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, 32 + WALL_THICKNESS / 2, ROOM_SIZE / 2))
)

-- Left Back Wall (along X = 0, extending +Z)
createWhiteSurface(
    "WhiteWallLeft",
    Vector3.new(WALL_THICKNESS, 32, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(-WALL_THICKNESS / 2, 16, ROOM_SIZE / 2))
)

-- Right Back Wall (along Z = 0, extending +X)
createWhiteSurface(
    "WhiteWallRight",
    Vector3.new(ROOM_SIZE, 32, WALL_THICKNESS),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, 16, -WALL_THICKNESS / 2))
)

-- Soft studio overhead light inside the white room
local lightAnchor = Instance.new("Part")
lightAnchor.Name = "StudioLightAnchor"
lightAnchor.Size = Vector3.new(1, 1, 1)
lightAnchor.Transparency = 1
lightAnchor.Anchored = true
lightAnchor.CanCollide = false
lightAnchor.Position = ROOM_ORIGIN + Vector3.new(12, 18, 12)
lightAnchor.Parent = sceneFolder

local pointLight = Instance.new("PointLight")
pointLight.Brightness = 1.6
pointLight.Range = 60
pointLight.Color = Color3.fromRGB(255, 255, 255)
pointLight.Shadows = true
pointLight.Parent = lightAnchor

-- 2. Spawn nish12345678910111 (UserId: 3762390414)
local function buildFallbackHumanoidDescription(): HumanoidDescription
    local desc = Instance.new("HumanoidDescription")
    -- Institutional White body colors (BrickColor 1001)
    local whiteColor = Color3.fromRGB(248, 248, 248)
    desc.HeadColor = whiteColor
    desc.TorsoColor = whiteColor
    desc.LeftArmColor = whiteColor
    desc.RightArmColor = whiteColor
    desc.LeftLegColor = whiteColor
    desc.RightLegColor = whiteColor

    -- Authentic R15 Man Package + Dylan Dynamic Head
    desc.Torso = 86500008
    desc.RightArm = 86500036
    desc.LeftArm = 86500054
    desc.LeftLeg = 86500064
    desc.RightLeg = 86500078
    desc.Head = 10687288296

    -- Authentic Accessories & Clothing worn by nish12345678910111
    desc.HairAccessory = "63690008" -- Pal Hair
    desc.HatAccessory = "5971691715" -- Ready Player Two Book
    desc.NeckAccessory = "376527115" -- Jade Necklace with Shell Pendant
    desc.ShouldersAccessory = "101876501735938" -- Glitch Animated Lightning Dragon Heads Black White
    desc.BackAccessory = "71409823493730" -- Silver Block
    desc.FrontAccessory = "113177392561115" -- RIA 2025 Interstellar Fishing Aura
    desc.GraphicTShirt = 8994181604 -- I love roblox
    desc.Pants = 398633812 -- Black Jeans with White Shoes

    desc.HeightScale = 0.98
    desc.WidthScale = 1.0
    desc.HeadScale = 1.0
    desc.DepthScale = 1.0
    desc.ProportionScale = 0.5
    desc.BodyTypeScale = 0.0
    return desc
end

local characterModel: Model
local ok, loadedModel = pcall(function()
    return Players:CreateHumanoidModelFromUserId(TARGET_USER_ID)
end)

if ok and loadedModel then
    characterModel = loadedModel
else
    local fallbackDesc = buildFallbackHumanoidDescription()
    characterModel = Players:CreateHumanoidModelFromDescription(fallbackDesc, Enum.HumanoidRigType.R15)
end

characterModel.Name = TARGET_USERNAME
characterModel.Parent = sceneFolder

-- 3. Pose nish12345678910111 sitting on the floor in the 90-degree corner
-- Facing diagonally outward from the corner along the (1, 0, 1) bisector
local cornerSeatPos = ROOM_ORIGIN + Vector3.new(1.85, 1.55, 1.85)
local lookDir = Vector3.new(1, 0, 1).Unit
local characterCFrame = CFrame.lookAt(cornerSeatPos, cornerSeatPos + lookDir)
characterModel:PivotTo(characterCFrame)

local humanoid = characterModel:FindFirstChildOfClass("Humanoid")
if humanoid then
    humanoid.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.None
    humanoid.Sit = true
end

-- Apply natural floor-sitting joint angles for R15 / R6 rigs
local function poseCharacterSitting(model: Model)
    for _, part in ipairs(model:GetDescendants()) do
        if part:IsA("BasePart") then
            part.Anchored = false
        end
    end
    local rootPart = model:FindFirstChild("HumanoidRootPart") :: BasePart?
    if rootPart then
        rootPart.Anchored = true
    end

    -- Adjust Motor6Ds for a relaxed corner-sitting posture (knees bent, hands resting on knees)
    local rightHip = model:FindFirstChild("RightHip", true) :: Motor6D?
    local leftHip = model:FindFirstChild("LeftHip", true) :: Motor6D?
    local rightKnee = model:FindFirstChild("RightKnee", true) :: Motor6D?
    local leftKnee = model:FindFirstChild("LeftKnee", true) :: Motor6D?
    local rightShoulder = model:FindFirstChild("RightShoulder", true) :: Motor6D?
    local leftShoulder = model:FindFirstChild("LeftShoulder", true) :: Motor6D?
    local rightElbow = model:FindFirstChild("RightElbow", true) :: Motor6D?
    local leftElbow = model:FindFirstChild("LeftElbow", true) :: Motor6D?
    local neck = model:FindFirstChild("Neck", true) :: Motor6D?

    if rightHip then rightHip.Transform = CFrame.Angles(math.rad(78), math.rad(-10), math.rad(8)) end
    if leftHip then leftHip.Transform = CFrame.Angles(math.rad(78), math.rad(10), math.rad(-8)) end
    if rightKnee then rightKnee.Transform = CFrame.Angles(math.rad(-65), 0, 0) end
    if leftKnee then leftKnee.Transform = CFrame.Angles(math.rad(-65), 0, 0) end
    if rightShoulder then rightShoulder.Transform = CFrame.Angles(math.rad(35), 0, math.rad(-18)) end
    if leftShoulder then leftShoulder.Transform = CFrame.Angles(math.rad(35), 0, math.rad(18)) end
    if rightElbow then rightElbow.Transform = CFrame.Angles(math.rad(42), 0, 0) end
    if leftElbow then leftElbow.Transform = CFrame.Angles(math.rad(42), 0, 0) end
    if neck then neck.Transform = CFrame.Angles(math.rad(-4), 0, 0) end
end

poseCharacterSitting(characterModel)

-- 4. Create the Fade-to-Black ScreenGui Overlay + Minimalist Timecode HUD
local guiParent: Instance = nil
pcall(function()
    guiParent = CoreGui
end)
if not guiParent or not guiParent.Parent then
    local localPlayer = Players.LocalPlayer
    if localPlayer then
        guiParent = localPlayer:WaitForChild("PlayerGui")
    else
        guiParent = Instance.new("Folder", sceneFolder)
    end
end

local existingGui = guiParent:FindFirstChild("NishWhiteRoomFadeGui")
if existingGui then
    existingGui:Destroy()
end

local screenGui = Instance.new("ScreenGui")
screenGui.Name = "NishWhiteRoomFadeGui"
screenGui.IgnoreGuiInset = true
screenGui.ResetOnSpawn = false
screenGui.DisplayOrder = 999999
screenGui.Parent = guiParent

local fadeFrame = Instance.new("Frame")
fadeFrame.Name = "BlackFadeOverlay"
fadeFrame.Size = UDim2.fromScale(1, 1)
fadeFrame.Position = UDim2.fromScale(0, 0)
fadeFrame.BackgroundColor3 = Color3.fromRGB(0, 0, 0)
fadeFrame.BorderSizePixel = 0
fadeFrame.BackgroundTransparency = 1.0 -- Starts completely transparent
fadeFrame.ZIndex = 10
fadeFrame.Parent = screenGui

-- 5. Animate Camera Focus + Slow Zoom (0:00 to 3:12) + Gradual Fade to Black
local camera = workspace.CurrentCamera
local initialCameraType = camera.CameraType
local initialFOV = camera.FieldOfView
camera.CameraType = Enum.CameraType.Scriptable

-- Start far back along the corner diagonal looking directly at nish12345678910111;
-- slowly dolly and tighten FOV from 0:00 (0s) to 3:12 (192s).
local startCamOffset = lookDir * 22.0 + Vector3.new(0, 2.2, 0)
local endCamOffset = lookDir * 3.8 + Vector3.new(0, 0.85, 0)
local startFocusOffset = Vector3.new(0, 0.45, 0) -- Full-body seated center at 0:00
local endFocusOffset = Vector3.new(0, 1.05, 0)   -- Head / upper chest focus at 3:12
local startFOV = 62.0
local endFOV = 24.0

local startTime = os.clock()
local connection: RBXScriptConnection

local function smoothstep(edge0: number, edge1: number, x: number): number
    local t = math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
end

connection = RunService.RenderStepped:Connect(function()
    local elapsed = os.clock() - startTime
    local progress = math.clamp(elapsed / TOTAL_DURATION, 0.0, 1.0)

    -- Keep sitting pose locked & add subtle breathing motion
    poseCharacterSitting(characterModel)
    local breathOffset = Vector3.new(0, math.sin(elapsed * 1.75) * 0.025, 0)

    -- Logarithmic/exponential interpolation for constant perceived zoom speed over 192 seconds
    local zoomAlpha = 1.0 - math.pow(1.0 - progress, 1.15)
    local currentCamPos = (cornerSeatPos + startCamOffset):Lerp(cornerSeatPos + endCamOffset, zoomAlpha)
    local currentFocusPos = (cornerSeatPos + startFocusOffset):Lerp(cornerSeatPos + endFocusOffset, zoomAlpha) + breathOffset

    camera.CFrame = CFrame.lookAt(currentCamPos, currentFocusPos)
    camera.FieldOfView = startFOV + (endFOV - startFOV) * zoomAlpha

    -- Gradual fade to black at the end (from 2:55 to 3:12)
    if elapsed >= FADE_START_TIME then
        local fadeProgress = smoothstep(FADE_START_TIME, TOTAL_DURATION - 0.5, elapsed)
        fadeFrame.BackgroundTransparency = 1.0 - fadeProgress
    else
        fadeFrame.BackgroundTransparency = 1.0
    end

    if elapsed >= TOTAL_DURATION then
        fadeFrame.BackgroundTransparency = 0.0 -- Hold pure black screen at 3:12
        connection:Disconnect()
    end
end)
