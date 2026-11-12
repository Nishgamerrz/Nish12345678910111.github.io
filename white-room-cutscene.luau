--!strict
--[[
    White Concrete Corner in a Dark Room — Cutscene (0:00 to 3:12)
    Loads and uses YOUR OWN live character (Players.LocalPlayer.Character),
    places them sitting in the corner of white concrete walls inside a dark room
    with dramatic Future/ShadowMap lighting and deep shadows, keeps the camera
    focused on your character while slowly zooming in from 0:00 to 3:12 (192s),
    and gradually fades the screen to black at the end.
]]

local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local Lighting = game:GetService("Lighting")
local CoreGui = game:GetService("CoreGui")

local TOTAL_DURATION = 192.0 -- 0:00 to 3:12 (192 seconds)
local FADE_START_TIME = 175.0 -- Gradual fade to black from 2:55 to 3:12

-- Clean up any existing cutscene folder/GUI if re-executed
local oldScene = workspace:FindFirstChild("NishWhiteConcreteCorner")
if oldScene then
    oldScene:Destroy()
end

local sceneFolder = Instance.new("Folder")
sceneFolder.Name = "NishWhiteConcreteCorner"
sceneFolder.Parent = workspace

-- ============================================================================
-- 1. DRAMATIC DARK ROOM LIGHTING + HIGH-CONTRAST SHADOWS
-- ============================================================================
pcall(function()
    -- Enable Future lighting in executors that support sethiddenproperty for crisp soft-penumbra shadows
    local shp = (getfenv() :: any).sethiddenproperty or (getfenv() :: any).set_hidden_property
    if shp then
        shp(Lighting, "Technology", Enum.Technology.Future)
    end
end)

pcall(function()
    Lighting.GlobalShadows = true
    Lighting.Ambient = Color3.fromRGB(7, 8, 11)
    Lighting.OutdoorAmbient = Color3.fromRGB(0, 0, 0)
    Lighting.Brightness = 0.15
    Lighting.ClockTime = 0
    Lighting.FogColor = Color3.fromRGB(5, 5, 8)
    Lighting.FogStart = 22
    Lighting.FogEnd = 75
    Lighting.EnvironmentDiffuseScale = 0.12
    Lighting.EnvironmentSpecularScale = 0.18
    Lighting.ShadowSoftness = 0.22
end)

-- Remove existing post-processing from previous runs and add dark-room cinema grading
local oldCC = Lighting:FindFirstChild("NishCornerColorGrading")
if oldCC then oldCC:Destroy() end

local colorCorrection = Instance.new("ColorCorrectionEffect")
colorCorrection.Name = "NishCornerColorGrading"
colorCorrection.Brightness = -0.02
colorCorrection.Contrast = 0.22
colorCorrection.Saturation = -0.12
colorCorrection.TintColor = Color3.fromRGB(246, 248, 255)
colorCorrection.Parent = Lighting

-- ============================================================================
-- 2. BUILD ENCLOSED DARK ROOM WITH WHITE CONCRETE CORNER WALLS
-- ============================================================================
local ROOM_ORIGIN = Vector3.new(0, 600, 0) -- High up so map geometry never interferes
local ROOM_SIZE = 56
local ROOM_HEIGHT = 28
local WALL_THICK = 3

local function makePart(
    name: string,
    size: Vector3,
    cf: CFrame,
    color: Color3,
    material: Enum.Material,
    castShadow: boolean
): Part
    local p = Instance.new("Part")
    p.Name = name
    p.Size = size
    p.CFrame = cf
    p.Anchored = true
    p.CanCollide = true
    p.Color = color
    p.Material = material
    p.CastShadow = castShadow
    p.TopSurface = Enum.SurfaceType.Smooth
    p.BottomSurface = Enum.SurfaceType.Smooth
    p.Reflectance = 0.01
    p.Parent = sceneFolder
    return p
end

local WHITE_CONCRETE = Color3.fromRGB(238, 238, 236)
local FLOOR_CONCRETE = Color3.fromRGB(222, 222, 220)
local DARK_ENCLOSURE = Color3.fromRGB(14, 14, 18)

-- White Concrete Left Corner Wall (X = 0 plane, extending along +Z)
makePart(
    "WhiteConcreteWallLeft",
    Vector3.new(WALL_THICK, ROOM_HEIGHT, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(-WALL_THICK / 2, ROOM_HEIGHT / 2, ROOM_SIZE / 2)),
    WHITE_CONCRETE,
    Enum.Material.Concrete,
    true
)

-- White Concrete Right Corner Wall (Z = 0 plane, extending along +X)
makePart(
    "WhiteConcreteWallRight",
    Vector3.new(ROOM_SIZE, ROOM_HEIGHT, WALL_THICK),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, ROOM_HEIGHT / 2, -WALL_THICK / 2)),
    WHITE_CONCRETE,
    Enum.Material.Concrete,
    true
)

-- Concrete Floor
makePart(
    "WhiteConcreteFloor",
    Vector3.new(ROOM_SIZE, WALL_THICK, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, -WALL_THICK / 2, ROOM_SIZE / 2)),
    FLOOR_CONCRETE,
    Enum.Material.Concrete,
    true
)

-- Enclosed Dark Ceiling & Far Walls (blocks all outside light so the room stays dark)
makePart(
    "DarkRoomCeiling",
    Vector3.new(ROOM_SIZE + WALL_THICK * 2, WALL_THICK, ROOM_SIZE + WALL_THICK * 2),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, ROOM_HEIGHT + WALL_THICK / 2, ROOM_SIZE / 2)),
    DARK_ENCLOSURE,
    Enum.Material.Concrete,
    true
)

makePart(
    "DarkFarWallX",
    Vector3.new(WALL_THICK, ROOM_HEIGHT, ROOM_SIZE),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE + WALL_THICK / 2, ROOM_HEIGHT / 2, ROOM_SIZE / 2)),
    DARK_ENCLOSURE,
    Enum.Material.Concrete,
    true
)

makePart(
    "DarkFarWallZ",
    Vector3.new(ROOM_SIZE, ROOM_HEIGHT, WALL_THICK),
    CFrame.new(ROOM_ORIGIN + Vector3.new(ROOM_SIZE / 2, ROOM_HEIGHT / 2, ROOM_SIZE + WALL_THICK / 2)),
    DARK_ENCLOSURE,
    Enum.Material.Concrete,
    true
)

-- Dramatic Overhead SpotLight focused on the corner to cast deep shadows onto the white concrete walls
local cornerTargetPos = ROOM_ORIGIN + Vector3.new(1.85, 1.5, 1.85)
local keyLightPos = ROOM_ORIGIN + Vector3.new(8.5, 11.5, 6.2)

local keyLightRig = Instance.new("Part")
keyLightRig.Name = "KeyShadowLight"
keyLightRig.Size = Vector3.new(0.5, 0.5, 0.5)
keyLightRig.Transparency = 1
keyLightRig.Anchored = true
keyLightRig.CanCollide = false
keyLightRig.CastShadow = false
keyLightRig.CFrame = CFrame.lookAt(keyLightPos, cornerTargetPos)
keyLightRig.Parent = sceneFolder

local keySpot = Instance.new("SpotLight")
keySpot.Name = "CornerKeySpot"
keySpot.Face = Enum.NormalId.Front
keySpot.Brightness = 3.4
keySpot.Range = 34
keySpot.Angle = 46
keySpot.Color = Color3.fromRGB(255, 250, 242)
keySpot.Shadows = true
keySpot.Parent = keyLightRig

-- Subtle Secondary Fill SpotLight from opposite high angle for concrete texture depth & dual shadow falloff
local fillLightPos = ROOM_ORIGIN + Vector3.new(5.2, 13.0, 9.5)
local fillLightRig = Instance.new("Part")
fillLightRig.Name = "FillShadowLight"
fillLightRig.Size = Vector3.new(0.5, 0.5, 0.5)
fillLightRig.Transparency = 1
fillLightRig.Anchored = true
fillLightRig.CanCollide = false
fillLightRig.CastShadow = false
fillLightRig.CFrame = CFrame.lookAt(fillLightPos, cornerTargetPos)
fillLightRig.Parent = sceneFolder

local fillSpot = Instance.new("SpotLight")
fillSpot.Name = "CornerFillSpot"
fillSpot.Face = Enum.NormalId.Front
fillSpot.Brightness = 0.75
fillSpot.Range = 28
fillSpot.Angle = 58
fillSpot.Color = Color3.fromRGB(210, 222, 245)
fillSpot.Shadows = true
fillSpot.Parent = fillLightRig

-- ============================================================================
-- 3. LOAD YOUR OWN LIVE CHARACTER & POSE SITTING IN THE CORNER
-- ============================================================================
local localPlayer = Players.LocalPlayer
local character: Model = nil :: any

if localPlayer then
    character = localPlayer.Character or localPlayer.CharacterAdded:Wait()
else
    -- Studio command-bar fallback if LocalPlayer is nil
    character = Players:CreateHumanoidModelFromUserId(3762390414)
    character.Parent = sceneFolder
end

-- Ensure all parts of your character cast crisp shadows on the white concrete corner
for _, desc in ipairs(character:GetDescendants()) do
    if desc:IsA("BasePart") then
        desc.CastShadow = true
    end
end

-- Disable default Animate script & stop all active animations so nothing overrides the sitting pose
local animateScript = character:FindFirstChild("Animate")
if animateScript and animateScript:IsA("LocalScript") then
    animateScript.Disabled = true
end

local humanoid = character:FindFirstChildOfClass("Humanoid")
if humanoid then
    humanoid.WalkSpeed = 0
    humanoid.JumpPower = 0
    pcall(function() humanoid.JumpHeight = 0 end)
    humanoid.AutoRotate = false
    humanoid.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.None

    local animator = humanoid:FindFirstChildOfClass("Animator")
    if animator then
        for _, track in ipairs(animator:GetPlayingAnimationTracks()) do
            track:Stop(0)
        end
    end
    for _, track in ipairs(humanoid:GetPlayingAnimationTracks()) do
        track:Stop(0)
    end
end

-- Position your character tucked into the 90-degree white concrete corner facing outward (45° diagonal)
local lookOutDir = Vector3.new(1, 0, 1).Unit
local isR6 = (humanoid and humanoid.RigType == Enum.HumanoidRigType.R6)
local hipHeightFromFloor = isR6 and 1.35 or 1.42
local seatWorldPos = ROOM_ORIGIN + Vector3.new(1.72, hipHeightFromFloor, 1.72)

local rootPart = character:WaitForChild("HumanoidRootPart") :: BasePart
rootPart.Anchored = true
rootPart.AssemblyLinearVelocity = Vector3.zero
rootPart.AssemblyAngularVelocity = Vector3.zero
character:PivotTo(CFrame.lookAt(seatWorldPos, seatWorldPos + lookOutDir))

-- Capture original Motor6D C0 matrices and apply floor-sitting C0 offsets
local originalC0s: { [Motor6D]: CFrame } = {}
for _, desc in ipairs(character:GetDescendants()) do
    if desc:IsA("Motor6D") then
        originalC0s[desc] = desc.C0
    end
end

local sittingOffsetsR15: { [string]: CFrame } = {
    -- Lower torso lowered so hips sit right on the concrete floor & back leans slightly into the corner
    ["Root"] = CFrame.new(0, -1.15, -0.18) * CFrame.Angles(math.rad(-10), 0, 0),
    -- Upper torso slight forward slouch
    ["Waist"] = CFrame.Angles(math.rad(-8), 0, 0),
    -- Thighs raised up/forward with knees slightly apart
    ["RightHip"] = CFrame.new(0.05, -0.05, 0) * CFrame.Angles(math.rad(104), math.rad(-14), math.rad(10)),
    ["LeftHip"] = CFrame.new(-0.05, -0.05, 0) * CFrame.Angles(math.rad(104), math.rad(14), math.rad(-10)),
    -- Knees bent downward so feet plant on the concrete floor
    ["RightKnee"] = CFrame.Angles(math.rad(-84), 0, 0),
    ["LeftKnee"] = CFrame.Angles(math.rad(-84), 0, 0),
    -- Ankles leveled on floor
    ["RightAnkle"] = CFrame.Angles(math.rad(-10), 0, 0),
    ["LeftAnkle"] = CFrame.Angles(math.rad(-10), 0, 0),
    -- Shoulders & elbows resting hands across knees
    ["RightShoulder"] = CFrame.Angles(math.rad(46), math.rad(-12), math.rad(-14)),
    ["LeftShoulder"] = CFrame.Angles(math.rad(46), math.rad(12), math.rad(14)),
    ["RightElbow"] = CFrame.Angles(math.rad(48), 0, math.rad(-8)),
    ["LeftElbow"] = CFrame.Angles(math.rad(48), 0, math.rad(8)),
    ["RightWrist"] = CFrame.Angles(math.rad(-12), 0, 0),
    ["LeftWrist"] = CFrame.Angles(math.rad(-12), 0, 0),
    -- Head angled slightly down looking toward camera
    ["Neck"] = CFrame.Angles(math.rad(-7), 0, 0),
}

local sittingOffsetsR6: { [string]: CFrame } = {
    ["RootJoint"] = CFrame.new(0, -1.2, -0.15) * CFrame.Angles(math.rad(-8), 0, 0),
    ["Right Hip"] = CFrame.new(0, 0.1, 0) * CFrame.Angles(0, 0, math.rad(88)),
    ["Left Hip"] = CFrame.new(0, 0.1, 0) * CFrame.Angles(0, 0, math.rad(-88)),
    ["Right Shoulder"] = CFrame.Angles(0, 0, math.rad(42)),
    ["Left Shoulder"] = CFrame.Angles(0, 0, math.rad(-42)),
    ["Neck"] = CFrame.Angles(math.rad(8), 0, 0),
}

local function enforceSittingPose(elapsed: number)
    -- Stop any newly triggered animations
    if humanoid then
        local animator = humanoid:FindFirstChildOfClass("Animator")
        if animator then
            for _, track in ipairs(animator:GetPlayingAnimationTracks()) do
                track:Stop(0)
            end
        end
    end

    local breathAngle = math.sin(elapsed * 1.6) * math.rad(1.4)

    for motor, baseC0 in pairs(originalC0s) do
        if motor.Parent then
            motor.Transform = CFrame.identity
            local offset = isR6 and sittingOffsetsR6[motor.Name] or sittingOffsetsR15[motor.Name]
            if offset then
                if motor.Name == "Waist" or motor.Name == "Neck" then
                    motor.C0 = baseC0 * offset * CFrame.Angles(breathAngle, 0, 0)
                else
                    motor.C0 = baseC0 * offset
                end
            end
        end
    end
end

enforceSittingPose(0)

-- ============================================================================
-- 4. FULLSCREEN FADE-TO-BLACK OVERLAY
-- ============================================================================
local guiParent: Instance = nil :: any
pcall(function()
    guiParent = CoreGui
end)
if not guiParent or not guiParent.Parent then
    if localPlayer then
        guiParent = localPlayer:WaitForChild("PlayerGui")
    else
        guiParent = sceneFolder
    end
end

local oldGui = guiParent:FindFirstChild("NishWhiteRoomFadeGui")
if oldGui then
    oldGui:Destroy()
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
fadeFrame.BackgroundTransparency = 1.0
fadeFrame.ZIndex = 100
fadeFrame.Parent = screenGui

-- ============================================================================
-- 5. CAMERA FOCUS & SLOW ZOOM (0:00 TO 3:12) + GRADUAL FADE TO BLACK
-- ============================================================================
local camera = workspace.CurrentCamera
camera.CameraType = Enum.CameraType.Scriptable

-- Camera starts back in the dark room looking at your character in the lit white concrete corner,
-- and slowly dollies + zooms in from 0:00 (0s) to 3:12 (192s).
local startCamPos = seatWorldPos + lookOutDir * 19.5 + Vector3.new(0, 1.85, 0)
local endCamPos = seatWorldPos + lookOutDir * 3.15 + Vector3.new(0, 0.32, 0)
local startFocusPos = seatWorldPos + Vector3.new(0, -0.15, 0) -- Full seated character & corner walls at 0:00
local endFocusPos = seatWorldPos + Vector3.new(0, 0.42, 0)    -- Upper body / head focus at 3:12
local startFOV = 60.0
local endFOV = 24.0

local function smoothstep(edge0: number, edge1: number, x: number): number
    local t = math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
end

local startTime = os.clock()
local steppedConn: RBXScriptConnection
local renderConn: RBXScriptConnection

steppedConn = RunService.Stepped:Connect(function()
    local elapsed = os.clock() - startTime
    enforceSittingPose(elapsed)
end)

renderConn = RunService.RenderStepped:Connect(function()
    local elapsed = os.clock() - startTime
    local progress = math.clamp(elapsed / TOTAL_DURATION, 0.0, 1.0)

    enforceSittingPose(elapsed)

    -- Constant-feel slow zoom from 0:00 to 3:12
    local zoomAlpha = 1.0 - math.pow(1.0 - progress, 1.18)
    local breathOffset = Vector3.new(0, math.sin(elapsed * 1.6) * 0.018, 0)

    local camPos = startCamPos:Lerp(endCamPos, zoomAlpha)
    local focusPos = startFocusPos:Lerp(endFocusPos, zoomAlpha) + breathOffset

    camera.CameraType = Enum.CameraType.Scriptable
    camera.CFrame = CFrame.lookAt(camPos, focusPos)
    camera.FieldOfView = startFOV + (endFOV - startFOV) * zoomAlpha

    -- Gradual fade to black at the end (2:55 -> 3:12)
    if elapsed >= FADE_START_TIME then
        local fadeAlpha = smoothstep(FADE_START_TIME, TOTAL_DURATION - 0.4, elapsed)
        fadeFrame.BackgroundTransparency = 1.0 - fadeAlpha
    else
        fadeFrame.BackgroundTransparency = 1.0
    end

    if elapsed >= TOTAL_DURATION then
        fadeFrame.BackgroundTransparency = 0.0
        steppedConn:Disconnect()
        renderConn:Disconnect()
    end
end)
