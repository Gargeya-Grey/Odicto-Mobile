import type { LucideIcon, LucideProps } from 'lucide-react';
import {
  ArrowLeft as ArrowLeftIcon,
  Check as CheckIcon,
  ChevronDown as ChevronDownIcon,
  ChevronRight as ChevronRightIcon,
  CircleAlert as CircleAlertIcon,
  Copy as CopyIcon,
  History as HistoryIcon,
  Info as InfoIcon,
  Lock as LockIcon,
  Mic as MicIcon,
  Settings as SettingsIcon,
  Settings2 as Settings2Icon,
} from 'lucide-react';

/** One line weight for every icon in the Android screens. */
export const ICON_STROKE = 1.75;

function thin(Icon: LucideIcon) {
  function ThinIcon(props: LucideProps) {
    return <Icon strokeWidth={ICON_STROKE} aria-hidden="true" {...props} />;
  }
  return ThinIcon;
}

export const ArrowLeft = thin(ArrowLeftIcon);
export const Check = thin(CheckIcon);
export const ChevronDown = thin(ChevronDownIcon);
export const ChevronRight = thin(ChevronRightIcon);
export const CircleAlert = thin(CircleAlertIcon);
export const Copy = thin(CopyIcon);
export const History = thin(HistoryIcon);
export const Info = thin(InfoIcon);
export const Lock = thin(LockIcon);
export const Mic = thin(MicIcon);
export const Settings = thin(SettingsIcon);
export const Settings2 = thin(Settings2Icon);
